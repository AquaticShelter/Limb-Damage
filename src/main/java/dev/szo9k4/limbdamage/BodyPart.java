package dev.szo9k4.limbdamage;

/** The four locational-damage zones, COD-style. ARMS and LEGS don't distinguish left/right —
 *  no FPS damage model does either, and the multiplier would be identical anyway. */
public enum BodyPart {
    HEAD,
    BODY,
    ARMS,
    LEGS
}
