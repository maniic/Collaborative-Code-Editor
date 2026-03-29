package com.collabeditor.redis.model;

/**
 * Canonical collaboration event types that relay through Redis pub/sub.
 *
 * <p>Only these event types cross instance boundaries. All other message
 * types remain instance-local or derive from these canonical events.
 */
public enum CanonicalEventType {
    OPERATION_APPLIED,
    PARTICIPANT_JOINED,
    PARTICIPANT_LEFT,
    PRESENCE_UPDATED
}
