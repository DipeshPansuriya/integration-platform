package io.integrationplatform.transform.api;

/**
 * E7 seam (arjun-fullstack, F5): the lookup source the transform engine reads - and the only edge between
 * platform-transform and the configuration's lookup tables, so transform never depends on platform-config
 * (architecture.md section 2, pre-plan Open 2 resolved this direction). Platform-config implements it (lane B1's
 * lookup edit path, T-163); lane B2's port resolves lookups through it. Interface only - no logic, no lane edits.
 */
public interface LookupSource {

  /** The current value of one lookup-table entry, or null when the table or key does not exist (T-163: immediate). */
  String entry(String tableName, String key);
}