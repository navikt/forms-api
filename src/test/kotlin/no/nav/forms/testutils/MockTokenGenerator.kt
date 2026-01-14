package no.nav.forms.testutils

import com.nimbusds.jose.JOSEObjectType
import no.nav.forms.config.AzureAdConfig
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback

const val MOCK_USER_GROUP_ID = "mock-user-group-id"
const val MOCK_ADMIN_GROUP_ID = "mock-admin-group-id"

fun MockOAuth2Server.createMockToken(
	navIdent: String = "A123456",
	userName: String = "Testesen, Test",
	groups: List<String> = listOf(MOCK_USER_GROUP_ID, MOCK_ADMIN_GROUP_ID)
): String {
	return this.issueToken(
		issuerId = AzureAdConfig.ISSUER,
		clientId = "application",
		tokenCallback = DefaultOAuth2TokenCallback(
			issuerId = AzureAdConfig.ISSUER,
			subject = navIdent,
			typeHeader = JOSEObjectType.JWT.type,
			audience = listOf("aud-localhost"),
			claims = mapOf(
				AzureAdConfig.CLAIM_NAME to userName,
				AzureAdConfig.CLAIM_NAV_IDENT to navIdent,
				AzureAdConfig.CLAIM_GROUPS to groups,
			),
			expiry = (2 * 3600).toLong()
		)
	).serialize()
}

fun MockOAuth2Server.createAdminToken(
	navIdent: String = "A123456",
	userName: String = "Testesen, Test",
): String {
	return createMockToken(
		navIdent = navIdent,
		userName = userName,
		groups = listOf(MOCK_USER_GROUP_ID, MOCK_ADMIN_GROUP_ID)
	)
}

fun MockOAuth2Server.createUserToken(
	navIdent: String = "A123456",
	userName: String = "Testesen, Test",
): String {
	return createMockToken(
		navIdent = navIdent,
		userName = userName,
		groups = listOf(MOCK_USER_GROUP_ID)
	)
}
