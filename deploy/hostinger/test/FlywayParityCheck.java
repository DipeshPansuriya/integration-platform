import org.flywaydb.core.Flyway;

/**
 * Parity check for the Flyway CLI pipeline: the application's own Flyway (same engine, classpath:db/migration of all
 * modules on the class path, the same lock_timeout init SQL as platform-app's flyway-lock-timeout.yml) looks at a database
 * the CLI migrated and must find nothing pending and a valid history. Never migrates. The password comes from the
 * environment (PARITY_DB_PASSWORD), not from an argument.
 * Run (single-file source launch): java -cp "<module src/main/resources dirs>;<flyway jars>" FlywayParityCheck.java <jdbc-url> <user>
 * Exit 0 = zero pending and validate OK; 1 = otherwise.
 */
public class FlywayParityCheck {
    public static void main(String[] args) {
        Flyway flyway = Flyway.configure()
                .dataSource(args[0], args[1], System.getenv("PARITY_DB_PASSWORD"))
                .initSql("SET lock_timeout = '5s'")
                .load();
        var info = flyway.info();
        var validation = flyway.validateWithResult();
        System.out.println("app Flyway " + Flyway.class.getProtectionDomain().getCodeSource().getLocation().getPath().replaceAll(".*/", "")
                + ": applied=" + info.applied().length + " pending=" + info.pending().length
                + " validate=" + (validation.validationSuccessful ? "OK" : "FAILED " + validation.errorDetails));
        System.exit(info.pending().length == 0 && validation.validationSuccessful ? 0 : 1);
    }
}
