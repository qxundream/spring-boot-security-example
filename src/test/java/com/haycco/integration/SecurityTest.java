package com.haycco.integration;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.response.ValidatableResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;

import static com.jayway.restassured.RestAssured.given;
import static com.jayway.restassured.RestAssured.when;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;

import org.haycco.spring.Application;
import org.haycco.spring.api.ApiController;
import org.haycco.spring.api.samplestuff.ServiceGateway;
import org.haycco.spring.infrastructure.AuthenticatedExternalWebService;
import org.haycco.spring.infrastructure.security.ExternalServiceAuthenticator;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = {Application.class, SecurityTest.SecurityTestConfig.class})
@WebAppConfiguration
@IntegrationTest("server.port:0")
public class SecurityTest {

    private static final String X_AUTH_USERNAME = "X-Auth-Username";
    private static final String X_AUTH_PASSWORD = "X-Auth-Password";
    private static final String X_AUTH_TOKEN = "X-Auth-Token";

    @Value("${local.server.port}")
    int port;

    @Value("${server.ssl.key-store}")
    String keystoreFile;

    @Value("${server.ssl.key-store-password}")
    String keystorePass;

    @Autowired
    ExternalServiceAuthenticator mockedExternalServiceAuthenticator;

    @Autowired
    ServiceGateway mockedServiceGateway;

    @Configuration
    public static class SecurityTestConfig {
        @Bean
        public ExternalServiceAuthenticator someExternalServiceAuthenticator() {
            return mock(ExternalServiceAuthenticator.class);
        }

        @Bean
        @Primary
        public ServiceGateway serviceGateway() {
            return mock(ServiceGateway.class);
        }
    }

    @Before
    public void setup() {
        RestAssured.baseURI = "https://localhost";
        RestAssured.keystore(keystoreFile, keystorePass);
        RestAssured.port = port;
        Mockito.reset(mockedExternalServiceAuthenticator, mockedServiceGateway);
    }

    @Test
    public void healthEndpoint_isAvailableToEveryone() {
        when().get("/health").
                then().statusCode(HttpStatus.OK.value()).body("status", equalTo("UP"));
    }

    @Test
    public void metricsEndpoint_withoutHayccoAdminCredentials_returnsUnauthorized() {
        when().get("/metrics").
                then().statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    public void metricsEndpoint_withInvalidHayccoAdminCredentials_returnsUnauthorized() {
        String username = "test_user_2";
        String password = "InvalidPassword";
        given().header(X_AUTH_USERNAME, username).header(X_AUTH_PASSWORD, password).
                when().get("/metrics").
                then().statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    public void metricsEndpoint_withCorrectHayccoAdminCredentials_returnsOk() {
        String username = "haycco_admin";
        String password = "remember_to_change_me_by_external_property_on_deploy";
        given().header(X_AUTH_USERNAME, username).header(X_AUTH_PASSWORD, password).
                when().get("/metrics").
                then().statusCode(HttpStatus.OK.value());
    }

    @Test
    public void authenticate_withoutPassword_returnsUnauthorized() {
        given().header(X_AUTH_USERNAME, "SomeUser").
                when().post(ApiController.AUTHENTICATE_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());

        BDDMockito.verifyNoMoreInteractions(mockedExternalServiceAuthenticator);
    }

    @Test
    public void authenticate_withoutUsername_returnsUnauthorized() {
        given().header(X_AUTH_PASSWORD, "SomePassword").
                when().post(ApiController.AUTHENTICATE_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());

        BDDMockito.verifyNoMoreInteractions(mockedExternalServiceAuthenticator);
    }

    @Test
    public void authenticate_withoutUsernameAndPassword_returnsUnauthorized() {
        when().post(ApiController.AUTHENTICATE_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());

        BDDMockito.verifyNoMoreInteractions(mockedExternalServiceAuthenticator);
    }

    @Test
    public void authenticate_withValidUsernameAndPassword_returnsToken() {
        authenticateByUsernameAndPasswordAndGetToken();
    }

    @Test
    public void authenticate_withInvalidUsernameOrPassword_returnsUnauthorized() {
        String username = "test_user_2";
        String password = "InvalidPassword";

        BDDMockito.when(mockedExternalServiceAuthenticator.authenticate(anyString(), anyString())).
                thenThrow(new BadCredentialsException("Invalid Credentials"));

        given().header(X_AUTH_USERNAME, username).header(X_AUTH_PASSWORD, password).
                when().post(ApiController.AUTHENTICATE_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    public void gettingStuff_withoutToken_returnsUnauthorized() {
        when().get(ApiController.STUFF_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    public void gettingStuff_withInvalidToken_returnsUnathorized() {
        given().header(X_AUTH_TOKEN, "InvalidToken").
                when().get(ApiController.STUFF_URL).
                then().statusCode(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    public void gettingStuff_withValidToken_returnsData() {
        String generatedToken = authenticateByUsernameAndPasswordAndGetToken();

        given().header(X_AUTH_TOKEN, generatedToken).
                when().get(ApiController.STUFF_URL).
                then().statusCode(HttpStatus.OK.value());
    }

    private String authenticateByUsernameAndPasswordAndGetToken() {
        String username = "test_user_2";
        String password = "ValidPassword";

        AuthenticatedExternalWebService authenticationWithToken = new AuthenticatedExternalWebService(username, null,
                AuthorityUtils.commaSeparatedStringToAuthorityList("ROLE_DOMAIN_USER"));
        BDDMockito.when(mockedExternalServiceAuthenticator.authenticate(eq(username), eq(password))).
                thenReturn(authenticationWithToken);

        ValidatableResponse validatableResponse = given().header(X_AUTH_USERNAME, username).
                header(X_AUTH_PASSWORD, password).
                when().post(ApiController.AUTHENTICATE_URL).
                then().statusCode(HttpStatus.OK.value());
        String generatedToken = authenticationWithToken.getToken();
        validatableResponse.body("token", equalTo(generatedToken));

        return generatedToken;
    }
}
