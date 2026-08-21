import type { DeletionStatus } from "./deletion-contract.js"

export function isClaimable(status: DeletionStatus, leaseExpired: boolean): boolean {
  switch (status) {
    case "RECEIVED":
    case "FAILED":
    case "PARTIALLY_FAILED":
      return true
    case "PROCESSING":
      return leaseExpired
    case "COMPLETED":
    case "CANCELLED":
      return false
    default: {
      const unsupported: never = status
      throw new Error(`Unsupported deletion status: ${String(unsupported)}`)
    }
  }
}
