package com.crisil.covenant.util;

import com.crisil.covenant.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.crisil.covenant.entity.DataSource;

/** Builds a JDBC {@link javax.sql.DataSource} from a {@link DataSource}'s connection config. */
public final class JdbcDataSourceFactory {

    private JdbcDataSourceFactory() {
    }

    public static javax.sql.DataSource build(DataSource source) {
        String url = source.config("jdbcUrl");
        if (url == null || url.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATA_SOURCE_CONFIG_MISSING",
                    "Data source '" + source.getName() + "' is missing required config: jdbcUrl");
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(url);
        String username = source.config("username");
        if (username != null && !username.isBlank()) {
            ds.setUsername(username);
        }
        String password = DataSourceSecrets.password(source);
        if (password != null) {
            ds.setPassword(password);
        }
        return ds;
    }
}
