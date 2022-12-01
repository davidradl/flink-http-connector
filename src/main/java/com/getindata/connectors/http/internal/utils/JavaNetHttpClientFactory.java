package com.getindata.connectors.http.internal.utils;

import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.util.StringUtils;

import com.getindata.connectors.http.internal.config.HttpConnectorConfigConstants;
import com.getindata.connectors.http.internal.security.SecurityContext;
import com.getindata.connectors.http.internal.security.SelfSignedTrustManager;

@Slf4j
@NoArgsConstructor(access = AccessLevel.NONE)
public class JavaNetHttpClientFactory {

    /**
     * Creates Java's {@link HttpClient} instance that will be using default, JVM shared {@link
     * java.util.concurrent.ForkJoinPool} for async calls.
     *
     * @param properties properties used to build {@link SSLContext}
     * @return new {@link HttpClient} instance.
     */
    public static HttpClient createClient(Properties properties) {

        SSLContext sslContext = getSslContext(properties);

        return HttpClient.newBuilder()
            .followRedirects(Redirect.NORMAL)
            .sslContext(sslContext)
            .build();
    }

    /**
     * Creates Java's {@link HttpClient} instance that will be using provided Executor for all async
     * calls.
     *
     * @param properties properties used to build {@link SSLContext}
     * @param executor   {@link Executor} for async calls.
     * @return new {@link HttpClient} instance.
     */
    public static HttpClient createClient(Properties properties, Executor executor) {

        SSLContext sslContext = getSslContext(properties);

        return HttpClient.newBuilder()
            .followRedirects(Redirect.NORMAL)
            .sslContext(sslContext)
            .executor(executor)
            .build();
    }

    /**
     * Creates an {@link SSLContext} based on provided properties.
     * <ul>
     *     <li>{@link HttpConnectorConfigConstants#ALLOW_SELF_SIGNED}</li>
     *     <li>{@link HttpConnectorConfigConstants#SERVER_TRUSTED_CERT}</li>
     *     <li>{@link HttpConnectorConfigConstants#PROP_DELIM}</li>
     *     <li>{@link HttpConnectorConfigConstants#CLIENT_CERT}</li>
     *     <li>{@link HttpConnectorConfigConstants#CLIENT_PRIVATE_KEY}</li>
     * </ul>
     *
     * @param properties properties used to build {@link SSLContext}
     * @return new {@link SSLContext} instance.
     */
    private static SSLContext getSslContext(Properties properties) {
        SecurityContext securityContext = createSecurityContext(properties);

        boolean selfSignedCert = Boolean.parseBoolean(
            properties.getProperty(HttpConnectorConfigConstants.ALLOW_SELF_SIGNED, "false"));

        String[] serverTrustedCerts = properties
            .getProperty(HttpConnectorConfigConstants.SERVER_TRUSTED_CERT, "")
            .split(HttpConnectorConfigConstants.PROP_DELIM);

        String clientCert = properties
            .getProperty(HttpConnectorConfigConstants.CLIENT_CERT, "");

        String clientPrivateKey = properties
            .getProperty(HttpConnectorConfigConstants.CLIENT_PRIVATE_KEY, "");

        if (serverTrustedCerts.length > 0) {
            for (String cert : serverTrustedCerts) {
                if (!StringUtils.isNullOrWhitespaceOnly(cert)) {
                    securityContext.addCertToTrustStore(cert);
                }
            }
        }

        if (!StringUtils.isNullOrWhitespaceOnly(clientCert)
            && !StringUtils.isNullOrWhitespaceOnly(clientPrivateKey)) {
            securityContext.addMTlsCerts(clientCert, clientPrivateKey);
        }

        // NOTE TrustManagers must be created AFTER adding all certificates to KeyStore.
        TrustManager[] trustManagers = getTrustedManagers(securityContext, selfSignedCert);
        return securityContext.getSslContext(trustManagers);
    }

    private static TrustManager[] getTrustedManagers(
        SecurityContext securityContext,
        boolean selfSignedCert) {

        TrustManager[] trustManagers = securityContext.getTrustManagers();

        if (selfSignedCert) {
            return wrapWithSelfSignedManagers(trustManagers).toArray(new TrustManager[0]);
        } else {
            return trustManagers;
        }
    }

    private static List<TrustManager> wrapWithSelfSignedManagers(TrustManager[] trustManagers) {
        log.warn("Creating Trust Managers for self-signed certificates - not Recommended. "
            + "Use [" + HttpConnectorConfigConstants.SERVER_TRUSTED_CERT + "] "
            + "connector property to add certificated as trusted.");

        List<TrustManager> selfSignedManagers = new ArrayList<>(trustManagers.length);
        for (TrustManager trustManager : trustManagers) {
            selfSignedManagers.add(new SelfSignedTrustManager((X509TrustManager) trustManager));
        }
        return selfSignedManagers;
    }

    /**
     * Creates a {@link SecurityContext} with empty {@link java.security.KeyStore} or loaded from
     * file.
     *
     * @param properties Properties for creating {@link SecurityContext}
     * @return new {@link SecurityContext} instance.
     */
    private static SecurityContext createSecurityContext(Properties properties) {

        String keyStorePath =
            properties.getProperty(HttpConnectorConfigConstants.KEY_STORE_PATH, "");

        if (StringUtils.isNullOrWhitespaceOnly(keyStorePath)) {
            return SecurityContext.create();
        } else {
            char[] storePassword =
                properties.getProperty(HttpConnectorConfigConstants.KEY_STORE_PASSWORD, "")
                    .toCharArray();
            if (storePassword.length == 0) {
                throw new RuntimeException("Missing password for provided KeyStore");
            }
            return SecurityContext.createFromKeyStore(keyStorePath, storePassword);
        }
    }

}
