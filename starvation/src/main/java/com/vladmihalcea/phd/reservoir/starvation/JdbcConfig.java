package com.vladmihalcea.phd.reservoir.starvation;

/**
 * JDBC connection coordinates shared by every pool the study opens.
 *
 * @author Vlad Mihalcea
 */
public record JdbcConfig(String url, String username, String password) {
}
