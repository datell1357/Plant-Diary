import type {
  Firestore,
  Transaction,
} from "firebase-admin/firestore";

const LOCKED_STATUSES = new Set(["PROCESSING", "PARTIALLY_FAILED", "COMPLETED"]);

export interface AccountMutationLock {
  isProcessing(ownerUid: string): Promise<boolean>;
}

export class AccountMutationLockedError extends Error {
  override readonly name = "AccountMutationLockedError";

  constructor(readonly ownerUid: string) {
    super("Account mutations are permanently frozen after account deletion starts");
  }
}

type AuthenticatedRequest = Readonly<{
  auth?: Readonly<{ uid: string }>;
}>;

export function withAccountMutationLock<
  Request extends AuthenticatedRequest,
  Result,
>(
  lock: AccountMutationLock,
  handler: (request: Request) => Promise<Result>,
): (request: Request) => Promise<Result> {
  return async (request) => {
    const ownerUid = request.auth?.uid;
    if (ownerUid !== undefined && (await lock.isProcessing(ownerUid))) {
      throw new AccountMutationLockedError(ownerUid);
    }
    return handler(request);
  };
}

export async function assertAccountMutationAllowed(
  transaction: Transaction,
  firestore: Firestore,
  ownerUid: string,
): Promise<void> {
  const snapshot = await transaction.get(
    firestore.doc(`accountDeletionRequests/${ownerUid}`),
  );
  if (
    snapshot.exists &&
    isAccountMutationLocked(snapshot.get("status"), snapshot.get("completedScopes"))
  ) throw new AccountMutationLockedError(ownerUid);
}

export async function runAccountMutationTransaction<T>(
  firestore: Firestore,
  ownerUid: string,
  operation: (transaction: Transaction) => Promise<T>,
  options?: Readonly<{ maxAttempts?: number }>,
): Promise<T> {
  return firestore.runTransaction(async (transaction) => {
    await assertAccountMutationAllowed(transaction, firestore, ownerUid);
    return operation(transaction);
  }, options);
}

export class FirestoreAccountMutationLock implements AccountMutationLock {
  constructor(private readonly firestore: Firestore) {}

  async isProcessing(ownerUid: string): Promise<boolean> {
    const snapshot = await this.firestore.doc(`accountDeletionRequests/${ownerUid}`).get();
    return snapshot.exists &&
      isAccountMutationLocked(snapshot.get("status"), snapshot.get("completedScopes"));
  }
}

export function isAccountMutationLocked(status: unknown, completedScopes: unknown): boolean {
  return LOCKED_STATUSES.has(status as string) ||
    (Array.isArray(completedScopes) && completedScopes.length > 0);
}
