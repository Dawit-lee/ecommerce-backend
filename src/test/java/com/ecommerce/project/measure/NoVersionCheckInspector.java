package com.ecommerce.project.measure;

/**
 * Test-only "no lock" baseline: neutralises the optimistic-lock predicate Hibernate adds to
 * product updates, so the unchanged main code runs as a plain read-modify-write.
 *
 *   update products set ... where product_id=? and version=?
 *   -> update products set ... where product_id=? and (version=? or true)
 *
 * The bind parameter is kept so Hibernate's parameter binding is unaffected; the row always matches,
 * so concurrent writers silently overwrite each other (lost update) instead of failing.
 */
public class NoVersionCheckInspector extends SqlCapture {

    static final String VERSION_PREDICATE = " and version=?";
    static final String NEUTRALISED = " and (version=? or true)";

    @Override
    public String inspect(String sql) {
        String rewritten = sql;
        if (sql.startsWith("update products ") && sql.endsWith(VERSION_PREDICATE)) {
            rewritten = sql.substring(0, sql.length() - VERSION_PREDICATE.length()) + NEUTRALISED;
        }
        return super.inspect(rewritten);
    }
}
