package com.ecommerce.project.measure;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Records every SQL statement Hibernate prepares, so query counts can be cross-checked
 * against Hibernate statistics and the raw SQL can be kept as evidence.
 */
public class SqlCapture implements StatementInspector {

    private static final List<String> STATEMENTS = new ArrayList<>();

    @Override
    public String inspect(String sql) {
        synchronized (STATEMENTS) {
            STATEMENTS.add(sql);
        }
        return sql;
    }

    public static void clear() {
        synchronized (STATEMENTS) {
            STATEMENTS.clear();
        }
    }

    public static List<String> snapshot() {
        synchronized (STATEMENTS) {
            return new ArrayList<>(STATEMENTS);
        }
    }
}
