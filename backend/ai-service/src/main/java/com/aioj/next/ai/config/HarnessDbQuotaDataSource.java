package com.aioj.next.ai.config;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The application's DataSource with the Harness quota in front (plan T1.6): on a Harness thread a
 * connection is handed out only with a permit, and closing it gives the permit back. Other
 * threads go straight to the pool. Transactions count as one connection held for their length.
 */
public class HarnessDbQuotaDataSource extends DelegatingDataSource {
    private final HarnessDbQuota quota;

    public HarnessDbQuotaDataSource(DataSource target, HarnessDbQuota quota) {
        super(target);
        this.quota = quota;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return HarnessDbQuota.isHarnessThread() ? withPermit(() -> super.getConnection()) : super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return HarnessDbQuota.isHarnessThread()
                ? withPermit(() -> super.getConnection(username, password))
                : super.getConnection(username, password);
    }

    @FunctionalInterface
    private interface ConnectionSource {
        Connection open() throws SQLException;
    }

    private Connection withPermit(ConnectionSource source) throws SQLException {
        boolean acquired;
        try {
            acquired = quota.tryAcquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SQLTransientConnectionException("interrupted while waiting for the Harness database quota", ex);
        }
        if (!acquired) {
            throw new SQLTransientConnectionException("Harness database quota exhausted (" + quota.capacity()
                    + " connections held, waited " + quota.waitTime().toMillis() + " ms)");
        }
        Connection connection;
        try {
            connection = source.open();
        } catch (SQLException | RuntimeException ex) {
            quota.release();
            throw ex;
        }
        return releasingOnClose(connection);
    }

    private Connection releasingOnClose(Connection target) {
        AtomicBoolean released = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getTargetException();
                    } finally {
                        if ("close".equals(method.getName()) && released.compareAndSet(false, true)) {
                            quota.release();
                        }
                    }
                });
    }
}
