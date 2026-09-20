package com.videoai.infra.mysql;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidAbstractDataSource.PhysicalConnectionInfo;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class DruidRecoveryTest {
    @Test @Timeout(10)
    void firstBackgroundConnectionFailureDoesNotClosePool() throws Exception {
        var attempts = new AtomicInteger();
        try (var source = new DruidDataSource() {
            @Override public PhysicalConnectionInfo createPhysicalConnection() throws SQLException {
                attempts.incrementAndGet();
                throw new SQLException("simulated temporary connection outage");
            }
        }) {
            source.setUrl("jdbc:mysql://127.0.0.1:1/test");
            source.setInitialSize(0); source.setMinIdle(0); source.setMaxActive(1);
            source.setMaxWait(300); source.setConnectionErrorRetryAttempts(0);
            source.setTimeBetweenConnectErrorMillis(50); source.setBreakAfterAcquireFailure(false);
            source.init();
            assertThrows(SQLException.class, source::getConnection);
            assertFalse(source.isClosed(), "Temporary acquisition failure must not close the pool");
            assertTrue(attempts.get() >= 2, "Connection creation should retry");
        }
    }
}
