package com.social.hotspot.etl;
import com.social.hotspot.etl.publish.AdsSnapshotPublisher;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/** Verify publication boundaries with JDBC doubles, without a live MySQL write. */
public class AdsSnapshotRegression {
    public static void main(String[] args) throws Exception {
        check(false); check(true);
        System.out.println("PASS: short-lived staging connections; fresh atomic publication connection; rollback and cleanup boundaries.");
    }

    private static void check(boolean fail) throws Exception {
        var actions = new ArrayList<String>();
        int[] inserts = {0};
        int[] connections = {0};
        int[] activeConnections = {0};
        java.util.function.Supplier<Connection> newConnection = () -> {
            connections[0]++;
            activeConnections[0]++;
            boolean[] closed = {false};
            return proxy(Connection.class, (name, args) -> {
            if (closed[0]) throw new SQLException("Connection already closed");
            switch (name) {
                case "setAutoCommit": actions.add("auto=" + args[0]); return null;
                case "commit": actions.add("COMMIT"); return null;
                case "rollback": actions.add("ROLLBACK"); return null;
                case "close": closed[0] = true; activeConnections[0]--; actions.add("CLOSE"); return null;
                case "createStatement": return proxy(Statement.class, (method, values) -> {
                    if (method.equals("executeUpdate")) { actions.add(values[0].toString()); return 0; }
                    if (method.equals("executeQuery")) {
                        int[] index = {-1};
                        return proxy(ResultSet.class, (field, params) -> {
                            if (field.equals("next")) return ++index[0] < 3;
                            if (field.equals("getString")) {
                                if (params[0].equals("Extra")) return index[0] == 0 ? "auto_increment" : "";
                                return List.of("id", "event_id", "content_count").get(index[0]);
                            }
                            return null;
                        });
                    }
                    return null;
                });
                case "prepareStatement":
                    String sql = args[0].toString();
                    return proxy(PreparedStatement.class, (method, values) -> {
                        if (method.equals("setString") && !"event".equals(values[1])) throw new AssertionError("Event filter lost");
                        if (method.equals("executeUpdate")) {
                            actions.add(sql);
                            if (sql.startsWith("INSERT") && ++inserts[0] == 2 && fail) throw new SQLException("injected failure");
                            return 1;
                        }
                        return null;
                    });
                default: return null;
            }
            });
        };
        Driver driver = new Driver() {
            public Connection connect(String url, Properties info) { return acceptsURL(url) ? newConnection.get() : null; }
            public boolean acceptsURL(String url) { return url.startsWith("jdbc:review-regression:"); }
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public Logger getParentLogger() { return Logger.getGlobal(); }
        };
        DriverManager.registerDriver(driver);
        try (var writer = new AdsSnapshotPublisher("jdbc:review-regression:test", "", "")) {
            if (connections[0] != 0) throw new AssertionError("Opened idle connection in constructor");
            writer.stage("ads_event_overview");
            if (activeConnections[0] != 0) throw new AssertionError("Connection held during Spark staging");
            writer.stage("ads_event_heat_trend");
            if (activeConnections[0] != 0) throw new AssertionError("Connection held during Spark staging");
            if (actions.stream().anyMatch(sql -> sql.startsWith("DELETE"))) throw new AssertionError("Deleted before staging");
            try { writer.publish("event"); if (fail) throw new AssertionError("Failure not propagated"); }
            catch (SQLException error) { if (!fail) throw error; }
        } finally { DriverManager.deregisterDriver(driver); }
        if (connections[0] != 5 || activeConnections[0] != 0) throw new AssertionError("Publication must use a fresh connection and close all connections");
        if (actions.contains("COMMIT") == fail || actions.contains("ROLLBACK") != fail) throw new AssertionError(actions);
        int terminal = actions.indexOf(fail ? "ROLLBACK" : "COMMIT");
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).startsWith("DROP") && i < terminal) throw new AssertionError("DDL committed in publication transaction");
            if (actions.get(i).startsWith("INSERT") && actions.get(i).contains("(`id`,")) throw new AssertionError("Auto IDs copied");
        }
    }

    private interface Call { Object invoke(String method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> call.invoke(method.getName(), args == null ? new Object[0] : args));
    }
}
