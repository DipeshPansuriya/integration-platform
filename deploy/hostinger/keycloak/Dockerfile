# Keycloak image with the showcase realm and theme baked in, production `start --optimized` (ADR-H-IPASS-01 Decision 4A).
#   docker build -f deploy/hostinger/keycloak/Dockerfile -t ipass/keycloak:26.8.1-<short-commit> .   (root context)
# The realm keeps ${VAR} placeholders (platform-realm.json); Keycloak fills them from the container environment on
# import. No admin secret here: KC_BOOTSTRAP_ADMIN_* arrive at run time from the server .env. Hostname and proxy
# settings are run-time options (compose.yaml), so one image serves any domain.
FROM quay.io/keycloak/keycloak:26.8.1@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b AS build
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=false
COPY compose/keycloak/themes/ /opt/keycloak/themes/
COPY compose/keycloak/platform-realm.json /opt/keycloak/data/import/platform-realm.json
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b
COPY --from=build /opt/keycloak/ /opt/keycloak/
ENV KC_DB=postgres KC_HEALTH_ENABLED=true
EXPOSE 8080
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
