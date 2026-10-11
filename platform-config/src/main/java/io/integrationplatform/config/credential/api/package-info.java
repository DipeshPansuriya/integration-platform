/**
 * The public target-credential surface of the configuration module (E5 T-103, T-185; ADR-007 Decision 7): what the
 * data plane needs to open a target credential at send time - the sealed rows ({@link
 * io.integrationplatform.config.credential.api.TargetCredentialVault}), the associated-data format ({@link
 * io.integrationplatform.config.credential.api.CredentialAad}), the deployment's key-encryption keys ({@link
 * io.integrationplatform.config.credential.api.KeyEncryptionKeys}) and the readiness signal for a missing key ({@link
 * io.integrationplatform.config.credential.api.KeyAvailability}) - plus the field register of rule 10. Decryption
 * itself lives only in platform-routing (rule 6). The only package of {@code config.credential} other modules use.
 */
package io.integrationplatform.config.credential.api;
