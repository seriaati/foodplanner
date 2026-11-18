package dev.seria.foodplanner;

import com.mojang.logging.LogUtils;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import team.creative.solonion.api.FoodCapability;
import team.creative.solonion.api.SOLOnionAPI;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

import java.util.*;
import java.util.stream.IntStream;

final class FoodFinder {
    private static final Logger LOG = LogUtils.getLogger();
    static final int MAX_MISSING = 2;
    private static final int MAX_DEPTH = 3;
    private static final int PRODUCERS_PER_INGREDIENT = 6;
    private static final int ITEMS_PER_INGREDIENT = 16;
    private static final int BUDGET = 300;

    record Step(ItemStack output, String station) {}

    record Option(ItemStack food, double diversity, int nutrition, String station, ResourceLocation recipeId,
                  List<ItemStack> uses, List<Step> steps, List<Ingredient> missing) {}

    record Results(List<Option> ready, List<Option> almost) {}

    private record Plan(List<ItemStack> uses, List<Step> steps, List<Ingredient> missing) {}

    private FoodFinder() {}

    static Results find(Player player, List<ItemStack> available) {
        long start = System.nanoTime();
        RegistryAccess registryAccess = player.level().registryAccess();
        List<Recipe<?>> recipes = ModdedRecipes.collect(player);

        Map<Item, List<Recipe<?>>> producers = new HashMap<>();
        for (Recipe<?> r : recipes) {
            ItemStack out = r.getResultItem(registryAccess);
            if (!out.isEmpty() && !needs(r).isEmpty()) producers.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(r);
        }
        for (List<Recipe<?>> list : producers.values()) list.sort(Comparator.comparingInt(r -> needs(r).size()));

        Solver solver = new Solver(merge(available), producers, registryAccess);
        FoodCapability eaten = SOLOnionAPI.getFoodCapability(player);
        Map<Item, Option> best = new HashMap<>();
        for (Recipe<?> r : recipes) {
            ItemStack out = r.getResultItem(registryAccess);
            if (out.isEmpty() || !out.isEdible() || needs(r).isEmpty()) continue;
            Option prev = best.get(out.getItem());
            if (prev != null && prev.missing().isEmpty() && prev.steps().isEmpty()) continue;

            Plan plan = solver.solve(r, 0);
            if (plan == null && (prev == null || !prev.missing().isEmpty())) plan = solver.solve(r, MAX_MISSING);
            if (plan == null) continue;

            FoodProperties food = out.getFoodProperties(player);
            Option o = new Option(out.copy(), eaten.simulateEat(player, out), food == null ? 0 : food.getNutrition(),
                    stationName(r), r.getId(), plan.uses(), plan.steps(), plan.missing());
            if (prev == null || isBetter(o, prev)) best.put(out.getItem(), o);
        }

        Comparator<Option> byValue = Comparator.comparingDouble(Option::diversity).reversed()
                .thenComparing(Comparator.comparingInt(Option::nutrition).reversed())
                .thenComparing(o -> o.food().getHoverName().getString());
        List<Option> ready = new ArrayList<>(), almost = new ArrayList<>();
        for (Option o : best.values()) (o.missing().isEmpty() ? ready : almost).add(o);
        ready.sort(byValue);
        almost.sort(Comparator.comparingInt((Option o) -> o.missing().size()).thenComparing(byValue));
        LOG.debug("Food Planner: {} ready, {} almost in {} ms", ready.size(), almost.size(), (System.nanoTime() - start) / 1_000_000);
        return new Results(ready, almost);
    }

    private static boolean isBetter(Option a, Option b) {
        if (a.missing().size() != b.missing().size()) return a.missing().size() < b.missing().size();
        return a.steps().size() < b.steps().size();
    }

    private static List<Ingredient> needs(Recipe<?> recipe) {
        List<Ingredient> needs = new ArrayList<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (!ing.isEmpty()) needs.add(ing);
        }
        if (recipe instanceof CookingPotRecipe pot && !pot.getOutputContainer().isEmpty()) {
            needs.add(Ingredient.of(pot.getOutputContainer()));
        }
        return needs;
    }

    private static List<ItemStack> merge(List<ItemStack> stacks) {
        List<ItemStack> merged = new ArrayList<>();
        outer:
        for (ItemStack s : stacks) {
            for (ItemStack m : merged) {
                if (ItemStack.isSameItemSameTags(m, s)) {
                    m.grow(s.getCount());
                    continue outer;
                }
            }
            merged.add(s.copy());
        }
        return merged;
    }

    /**
     * Greedy planner: each ingredient is taken from leftovers of earlier steps, then from the pool, then crafted
     * from a sub-recipe (up to MAX_DEPTH deep), and finally counted as missing if the allowance permits.
     * Working inventory = pool copies at [0, pool.size()), crafted outputs appended after.
     */
    private static final class Solver {
        private final List<ItemStack> pool;
        private final Map<Item, List<Recipe<?>>> producers;
        private final RegistryAccess registryAccess;
        private final Map<Ingredient, int[]> poolMatches = new IdentityHashMap<>();
        private final Map<Ingredient, List<Recipe<?>>> producerCache = new IdentityHashMap<>();

        private List<ItemStack> inv;
        private final List<Integer> taken = new ArrayList<>();
        private List<Step> steps;
        private List<Ingredient> missing;
        private Set<Item> inProgress;
        private int missingLeft;
        private int budget;

        Solver(List<ItemStack> pool, Map<Item, List<Recipe<?>>> producers, RegistryAccess registryAccess) {
            this.pool = pool;
            this.producers = producers;
            this.registryAccess = registryAccess;
        }

        Plan solve(Recipe<?> recipe, int allowedMissing) {
            inv = new ArrayList<>();
            for (ItemStack s : pool) inv.add(s.copy());
            taken.clear();
            steps = new ArrayList<>();
            missing = new ArrayList<>();
            inProgress = new HashSet<>();
            inProgress.add(recipe.getResultItem(registryAccess).getItem());
            missingLeft = allowedMissing;
            budget = BUDGET;
            if (!consume(recipe, 0)) return null;

            List<ItemStack> uses = new ArrayList<>();
            for (int i = 0; i < pool.size(); i++) {
                int used = pool.get(i).getCount() - inv.get(i).getCount();
                if (used > 0) uses.add(pool.get(i).copyWithCount(used));
            }
            return new Plan(uses, steps, missing);
        }

        private boolean consume(Recipe<?> recipe, int depth) {
            List<Ingredient> needs = needs(recipe);
            needs.sort(Comparator.comparingInt(ing -> matches(ing).length));
            for (Ingredient ing : needs) {
                if (!satisfy(ing, depth)) return false;
            }
            return true;
        }

        private boolean satisfy(Ingredient ing, int depth) {
            if (--budget < 0) return false;
            for (int i = inv.size() - 1; i >= pool.size(); i--) {
                if (inv.get(i).getCount() > 0 && ing.test(inv.get(i))) return take(i);
            }
            for (int i : matches(ing)) {
                if (inv.get(i).getCount() > 0) return take(i);
            }
            if (depth < MAX_DEPTH) {
                for (Recipe<?> sub : producersFor(ing)) {
                    ItemStack out = sub.getResultItem(registryAccess);
                    if (inProgress.contains(out.getItem())) continue;
                    int invSize = inv.size(), takenCount = taken.size();
                    int stepCount = steps.size(), missingCount = missing.size(), missingLeftBefore = missingLeft;

                    inProgress.add(out.getItem());
                    boolean ok = consume(sub, depth + 1);
                    inProgress.remove(out.getItem());
                    if (ok) {
                        steps.add(new Step(out.copy(), stationName(sub)));
                        inv.add(out.copyWithCount(out.getCount() - 1));
                        return true;
                    }
                    while (taken.size() > takenCount) inv.get(taken.remove(taken.size() - 1)).grow(1);
                    while (inv.size() > invSize) inv.remove(inv.size() - 1);
                    steps.subList(stepCount, steps.size()).clear();
                    missing.subList(missingCount, missing.size()).clear();
                    missingLeft = missingLeftBefore;
                    if (budget < 0) return false;
                }
            }
            if (missingLeft > 0) {
                missingLeft--;
                missing.add(ing);
                return true;
            }
            return false;
        }

        private boolean take(int i) {
            inv.get(i).shrink(1);
            taken.add(i);
            return true;
        }

        private int[] matches(Ingredient ing) {
            return poolMatches.computeIfAbsent(ing, k -> IntStream.range(0, pool.size()).filter(j -> k.test(pool.get(j))).toArray());
        }

        private List<Recipe<?>> producersFor(Ingredient ing) {
            return producerCache.computeIfAbsent(ing, k -> {
                LinkedHashSet<Recipe<?>> found = new LinkedHashSet<>();
                ItemStack[] items = k.getItems();
                for (int i = 0; i < items.length && i < ITEMS_PER_INGREDIENT; i++) {
                    found.addAll(producers.getOrDefault(items[i].getItem(), List.of()));
                }
                return found.stream().limit(PRODUCERS_PER_INGREDIENT).toList();
            });
        }
    }

    static String stationName(Recipe<?> recipe) {
        if (recipe instanceof ModdedRecipes.Derived d) return d.station();
        ResourceLocation id = ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        if (id == null) return "?";
        return switch (id.toString()) {
            case "minecraft:crafting" -> "Crafting";
            case "minecraft:smelting" -> "Furnace";
            case "minecraft:smoking" -> "Smoker";
            case "minecraft:campfire_cooking" -> "Campfire";
            case "farmersdelight:cooking" -> "Cooking Pot";
            case "farmersdelight:cutting" -> "Cutting Board";
            default -> {
                StringBuilder sb = new StringBuilder();
                for (String word : id.getPath().split("_")) {
                    if (word.isEmpty()) continue;
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
                yield sb.toString();
            }
        };
    }
}
