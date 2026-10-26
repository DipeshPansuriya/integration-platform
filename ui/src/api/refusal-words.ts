// T178a-3 (row 372 L2): the server refuses an approval of a DB table with a coded sentence (DB_ACCOUNT_TOO_WIDE,
// DB_ACCOUNT_UNVERIFIED). This is the one place the console turns those codes into plain words. The server's text stays
// the authority: only a refusal that STARTS with one of the two codes is reworded, and the server's own detail (the
// account, the extra rights) is kept; any other text is returned untouched.

const ORDER = 'Order: store the account password in the vault, grant the account only INSERT and SELECT on the table, then approve.';

const TOO_WIDE = /^DB_ACCOUNT_TOO_WIDE:\s*(.*)$/s;
const UNVERIFIED = /^DB_ACCOUNT_UNVERIFIED:\s*(.*)$/s;

const PROBE_STEP: Array<[RegExp, string]> = [
  [/\(NO_CREDENTIAL\)/, 'No password is stored for the database account yet - store the account password first.'],
  [/\(NO_ACCOUNT\)/, 'The system has no account name set - add the database account name first.'],
  [/\(TABLE_NOT_FOUND\)/, 'The table was not found in the database, or the account cannot see it - check the table name and the account.'],
  [/\(AUTH_REFUSED\)/, 'The database refused the account login - check the stored password and the account name.'],
  [/did not answer/, 'The database did not answer - check the host and port, then try again.']
];

/** The text as an operator should read it; a refusal this console does not know is returned as it came. */
export function refusalWords(text: string): string {
  const wide = TOO_WIDE.exec(text);
  if (wide) {
    return `The database account has more rights than INSERT and SELECT on this table, so it cannot be approved. ${wide[1]} Narrow the account to INSERT and SELECT only, then approve again.`;
  }
  const open = UNVERIFIED.exec(text);
  if (open) {
    const step = PROBE_STEP.find(([pattern]) => pattern.test(open[1]))?.[1]
      ?? "The platform could not read the account's rights, so it cannot confirm the account is narrow.";
    return `${step} An approval is refused until the account's rights can be read. ${ORDER}`;
  }
  return text;
}
