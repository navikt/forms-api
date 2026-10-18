package no.nav.forms

import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class TestDatabaseConfiguration {

	@Bean(initMethod = "start", destroyMethod = "stop")
	fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:18")
		.withDatabaseName("forms-api-preprod-db")

	@Bean
	fun dataSource(postgres: PostgreSQLContainer): PGSimpleDataSource = PGSimpleDataSource().apply {
		setURL(postgres.jdbcUrl)
		user = postgres.username
		password = postgres.password
	}
}
