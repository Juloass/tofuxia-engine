package fr.tofuxia.app;

import io.github.juloass.identifier.Identifier;
import io.github.juloass.input.ButtonTransitionKind;
import io.github.juloass.input.InputVector2;

import java.util.Objects;
import java.util.Optional;

/** One ordered logical input command from the platform boundary. */
public record InputCommand(long sequence, Identifier action, ButtonTransitionKind kind,
                           Optional<InputVector2> pointerPosition) {
    public InputCommand {
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(kind, "kind");
        pointerPosition = Objects.requireNonNull(pointerPosition, "pointerPosition");
    }

    public InputCommand(long sequence, Identifier action, ButtonTransitionKind kind) {
        this(sequence, action, kind, Optional.empty());
    }
}
