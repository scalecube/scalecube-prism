/**
 * Durable local store for the owning member's own slice and its monotonic version. Write-ahead
 * (fsync before advertise) so a restart with a stable {@code memberId} resumes at the next version
 * instead of being rejected as stale. Remote slices are never persisted — they re-sync on join.
 */
package io.scalecube.prism.persistence;
