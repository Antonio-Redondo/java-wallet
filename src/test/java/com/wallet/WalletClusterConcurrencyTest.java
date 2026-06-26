package com.wallet;

import com.wallet.dto.TransferRequest;
import com.wallet.exception.InsufficientFundsException;
import com.wallet.model.Account;
import com.wallet.model.LedgerEntry;
import com.wallet.repository.AccountRepository;
import com.wallet.repository.LedgerEntryRepository;
import com.wallet.service.WalletService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves correctness not just across THREADS (see {@link WalletServiceConcurrencyTest})
 * but across NODES.
 *
 * <p>Two independent Spring Boot application contexts are started — each with its
 * own connection pool and Hibernate session factory, i.e. a separate "wallet
 * server" — pointing at <strong>one shared PostgreSQL</strong> (Testcontainers).
 * Overlapping random transfers are then fired at the same accounts, split across
 * both nodes simultaneously.
 *
 * <p>The two nodes share no memory — only the database. So if cluster-safety
 * relied on anything JVM-local (a {@code synchronized} block, an in-memory map),
 * conservation would break here. It holds because the pessimistic row locks
 * ({@code SELECT ... FOR UPDATE}) and the idempotency-key unique constraint are
 * enforced by PostgreSQL for every connection from every node, and because the
 * schema is the one applied by the Flyway migration (Hibernate runs with
 * {@code ddl-auto=validate}).
 *
 * <p>Requires Docker; the test is skipped (not failed) when Docker is not
 * reachable by Testcontainers. On Linux/CI with a standard Docker socket it runs
 * out of the box. On Windows + Docker Desktop you may need to point Testcontainers
 * at the engine pipe, e.g. {@code DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine}.
 */
class WalletClusterConcurrencyTest {

    private static final int ACCOUNTS = 8;
    private static final int THREADS = 8;
    private static final int TRANSFERS_PER_THREAD = 150;
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000.00");

    private static PostgreSQLContainer<?> postgres;
    private static ConfigurableApplicationContext nodeA;
    private static ConfigurableApplicationContext nodeB;

    @BeforeAll
    static void startCluster() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the cluster concurrency test");

        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("wallet")
                .withUsername("wallet")
                .withPassword("wallet");
        postgres.start();

        // Start two nodes sequentially: the first applies the Flyway migration,
        // the second finds the schema already at the latest version and no-ops.
        nodeA = startNode("node-A");
        nodeB = startNode("node-B");
    }

    private static ConfigurableApplicationContext startNode(String name) {
        return new SpringApplicationBuilder(WalletApplication.class)
                .properties(
                        "spring.profiles.active=postgres",
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        // Each node binds its own random HTTP port, like a real
                        // pair of servers behind a load balancer.
                        "server.port=0",
                        "spring.application.name=" + name)
                .run();
    }

    @AfterAll
    static void stopCluster() {
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void concurrentTransfersAcrossNodesConserveMoney() throws InterruptedException {
        WalletService serviceA = nodeA.getBean(WalletService.class);
        WalletService serviceB = nodeB.getBean(WalletService.class);
        AccountRepository accounts = nodeA.getBean(AccountRepository.class);
        LedgerEntryRepository ledger = nodeA.getBean(LedgerEntryRepository.class);

        // Seed the accounts (via node A) with an opening deposit each.
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            Account account = serviceA.createAccount("EUR");
            serviceA.transfer(new TransferRequest(null, null, account.getId(), INITIAL_BALANCE, "EUR"));
            ids.add(account.getId());
        }
        BigDecimal expectedTotal = INITIAL_BALANCE.multiply(BigDecimal.valueOf(ACCOUNTS));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            // Half the worker threads drive node A, half drive node B, so the
            // same accounts are mutated concurrently from both nodes.
            final WalletService node = (t % 2 == 0) ? serviceA : serviceB;
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                        UUID from = ids.get(ThreadLocalRandom.current().nextInt(ACCOUNTS));
                        UUID to = ids.get(ThreadLocalRandom.current().nextInt(ACCOUNTS));
                        if (from.equals(to)) {
                            continue;
                        }
                        BigDecimal amount = new BigDecimal(ThreadLocalRandom.current().nextInt(1, 50));
                        try {
                            node.transfer(new TransferRequest(null, from, to, amount, "EUR"));
                        } catch (InsufficientFundsException ignored) {
                            // Expected sometimes; the transfer is rejected and rolled back.
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS))
                .as("all worker threads finished").isTrue();
        pool.shutdownNow();

        // Invariant 1: total money is unchanged across the whole cluster.
        BigDecimal total = ids.stream()
                .map(id -> accounts.findById(id).orElseThrow().getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).as("total money conserved across both nodes")
                .isEqualByComparingTo(expectedTotal);

        // Invariant 2: each balance equals the sum of its ledger entries.
        for (UUID id : ids) {
            BigDecimal fromLedger = ledger.findByAccountIdOrderByTimestampAscIdAsc(id).stream()
                    .map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(fromLedger)
                    .as("ledger matches balance for account %s", id)
                    .isEqualByComparingTo(accounts.findById(id).orElseThrow().getBalance());
        }
    }
}
