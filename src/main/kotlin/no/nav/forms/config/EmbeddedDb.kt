package no.nav.forms.config

import com.opentable.db.postgres.embedded.EmbeddedPostgres
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import javax.sql.DataSource

@Profile("test | local")
@Configuration
class EmbeddedDb {

	private var embeddedPostgres: EmbeddedPostgres = EmbeddedPostgres.builder().setTag("16").start()

	@Bean
	fun embeddedPostgres(): DataSource {
		return embeddedPostgres.postgresDatabase
	}

}
