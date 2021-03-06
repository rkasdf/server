package org.domeos.framework.api.model.global;

/**
 * Created by feiliu206363 on 2016/1/20.
 */
public enum GlobalType {
    SERVER,
    LDAP_SERVER,
    LDAP_PREFIX,
    GITLAB,
    GITHUB,
    BUILD_IMAGE,
    REGISTRY_URL,
    REGISTRY_DESCRIPTION,
    REGISTRY_STATUS,
    REGISTRY_CERTIFICATION,
    REGISTRY_AUTH_PRIVATE_KEY,
    REGISTRY_ISSUER,
    REGISTRY_SERVICE,
    WEBSSH,
    MONITOR_QUERY,
    MONITOR_GRAPH,
    MONITOR_TRANSFER,
    MONITOR_HBS,
    MONITOR_JUDGE,
    MONITOR_ALARM,
    MONITOR_SENDER,
    MONITOR_NODATA,
    MONITOR_REDIS,
    MONITOR_API_SMS,
    MONITOR_API_MAIL,
    CI_CLUSTER_HOST,
    CI_CLUSTER_NAMESPACE,
    PUBLIC_REGISTRY_URL,
    CI_CLUSTER_ID,
    CI_CLUSTER_NAME,
    REGISTRY_AUTH,

    //add
    CI_CLUSTER_USERNAME,
    CI_CLUSTER_PASSWORD,
    CI_CLUSTER_OAUTHTOKEN
}
