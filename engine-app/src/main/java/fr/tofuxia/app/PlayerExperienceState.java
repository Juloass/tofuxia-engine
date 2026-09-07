package fr.tofuxia.app;

import io.github.juloass.localization.TextComponent;

import java.util.List;
import java.util.Map;

/** Immutable, server-derived presentation model for the selected character. */
public record PlayerExperienceState(String characterId, String name, int level, String divinityPathId,
                                    List<String> learnedSpellIds, Inventory inventory,
                                    List<EquipmentSlot> equipmentSlots, List<Statistic> statistics,
                                    List<Status> statuses, List<Spell> spells, String activeSavePointId, String diagnostic) {
    public record Stack(String stackId, String itemId, TextComponent displayName, String presentationId,
                        int quantity, double podsPerUnit, Map<String, String> metadata,
                        List<String> compatibleSlotIds, List<String> requirements,
                        List<String> modifiers, boolean consumable) {
        public Stack { metadata = Map.copyOf(metadata); compatibleSlotIds = List.copyOf(compatibleSlotIds);
            requirements = List.copyOf(requirements); modifiers = List.copyOf(modifiers); }
        public Stack(String stackId, String itemId, String displayName, String presentationId,
                     int quantity, double podsPerUnit, Map<String, String> metadata,
                     List<String> compatibleSlotIds, List<String> requirements,
                     List<String> modifiers, boolean consumable) {
            this(stackId, itemId, TextComponent.literal(displayName), presentationId, quantity, podsPerUnit, metadata,
                    compatibleSlotIds, requirements, modifiers, consumable);
        }
        public double totalPods() { return podsPerUnit * quantity; }
    }
    public record Inventory(long revision, List<Stack> stacks, Map<String, String> equipment,
                            double carriedPods, double effectivePodsCapacity, String transactionDiagnostic) {
        public static final Inventory EMPTY = new Inventory(0, List.of(), Map.of(), 0, 0, "");
        public Inventory { stacks = List.copyOf(stacks); equipment = Map.copyOf(equipment);
            transactionDiagnostic = transactionDiagnostic == null ? "" : transactionDiagnostic; }
    }
    public record EquipmentSlot(String id, TextComponent displayName, int order) {
        public EquipmentSlot(String id, String displayName, int order) { this(id, TextComponent.literal(displayName), order); }
    }
    public record Contribution(String sourceId, String layer, String operation, double configuredValue,
                               double before, double after) {}
    public record Statistic(String id, TextComponent displayName, double baseValue, double derivedValue,
                            double effectiveValue, List<Contribution> contributions) {
        public Statistic { contributions = List.copyOf(contributions); }
        public Statistic(String id, String displayName, double baseValue, double derivedValue,
                         double effectiveValue, List<Contribution> contributions) {
            this(id, TextComponent.literal(displayName), baseValue, derivedValue, effectiveValue, contributions);
        }
    }
    public record Status(long instanceId, String definitionId, TextComponent displayName, String scope, String sourceId,
                         String clock, long expiresAt, int stacks, String stacking, List<String> modifiers) {
        public Status { modifiers = List.copyOf(modifiers); }
        public Status(long instanceId, String definitionId, String displayName, String scope, String sourceId,
                      String clock, long expiresAt, int stacks, String stacking, List<String> modifiers) {
            this(instanceId, definitionId, TextComponent.literal(displayName), scope, sourceId, clock, expiresAt,
                    stacks, stacking, modifiers);
        }
        public boolean combatOnly() { return scope.equals("COMBAT"); }
    }
    public record Spell(String id, TextComponent displayName, int apCost, int minimumRange, int maximumRange,
                        boolean lineOfSight, String targetRuleId, String elementId, String actionId,
                        double basePower, Map<String, Double> resourceScaling, String iconId,
                        String elementalEffectId, String actionEffectId, String audioHookId,
                        boolean intentionalFallback) {
        public Spell { resourceScaling = Map.copyOf(resourceScaling); }
        public Spell(String id, String displayName, int apCost, int minimumRange, int maximumRange,
                     boolean lineOfSight, String targetRuleId, String elementId, String actionId,
                     double basePower, Map<String, Double> resourceScaling, String iconId,
                     String elementalEffectId, String actionEffectId, String audioHookId,
                     boolean intentionalFallback) {
            this(id, TextComponent.literal(displayName), apCost, minimumRange, maximumRange, lineOfSight,
                    targetRuleId, elementId, actionId, basePower, resourceScaling, iconId, elementalEffectId,
                    actionEffectId, audioHookId, intentionalFallback);
        }
        public double preview(String resourceKind, double power) {
            return Math.max(0, basePower * resourceScaling.getOrDefault(resourceKind, 1d) * (1 + power / 100d));
        }
    }
    public static final PlayerExperienceState EMPTY = new PlayerExperienceState("", "", 0, "", List.of(),
            Inventory.EMPTY, List.of(), List.of(), List.of(), List.of(), "", "");
    public PlayerExperienceState { learnedSpellIds = List.copyOf(learnedSpellIds); equipmentSlots = List.copyOf(equipmentSlots);
        statistics = List.copyOf(statistics); statuses = List.copyOf(statuses); spells = List.copyOf(spells);
        diagnostic = diagnostic == null ? "" : diagnostic; }
    public PlayerExperienceState withInventoryDiagnostic(String value) {
        return new PlayerExperienceState(characterId, name, level, divinityPathId, learnedSpellIds,
                new Inventory(inventory.revision(), inventory.stacks(), inventory.equipment(), inventory.carriedPods(),
                        inventory.effectivePodsCapacity(), value), equipmentSlots, statistics, statuses, spells, activeSavePointId, diagnostic);
    }
}
