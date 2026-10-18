package no.nav.forms.config

import org.postgresql.ds.PGSimpleDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.testcontainers.postgresql.PostgreSQLContainer

@Profile("test | local")
@Configuration
class EmbeddedDb {

	@Bean(initMethod = "start", destroyMethod = "stop")
	fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:18")
		.withDatabaseName("forms-api-local")

	@Bean
	fun embeddedPostgres(postgres: PostgreSQLContainer): PGSimpleDataSource = PGSimpleDataSource().apply {
		setURL(postgres.jdbcUrl)
		user = postgres.username
		password = postgres.password
	}

}
