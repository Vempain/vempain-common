package fi.poltsi.vempain.common.api;

/**
 * Scope of a service API token. Each backend chooses which scopes it issues and accepts.
 */
public enum ApiTokenScopeEnum {
    FILE_SERVICE,
    MCP_READ
}
