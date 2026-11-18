package dev.seria.foodplanner;

import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import vectorwing.farmersdelight.common.block.FeastBlock;
import vectorwing.farmersdelight.common.registry.ModItems;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Rewrites food sources the planner can't read from plain recipes into recipes with real ingredients and results.
 * Cuisine Delight, Brewin' and Chewin' and Farmer's Respite are read via reflection so they stay optional.
 */
final class ModdedRecipes {
    record Derived(ResourceLocation id, ItemStack result, NonNullList<Ingredient> needs, String station) implements Recipe<Container> {
        @Override public boolean matches(Container c, Level level) { return false; }
        @Override public ItemStack assemble(Container c, RegistryAccess access) { return result.copy(); }
        @Override public boolean canCraftInDimensions(int w, int h) { return false; }
        @Override public ItemStack getResultItem(RegistryAccess access) { return result; }
        @Override public NonNullList<Ingredient> getIngredients() { return needs; }
        @Override public ResourceLocation getId() { return id; }
        @Override public RecipeSerializer<?> getSerializer() { return null; }
        @Override public RecipeType<?> getType() { return null; }
    }

    private static final String CUISINE = "cuisinedelight:plate_cuisine";
    private static final String KETTLE = "farmersrespite:brewing";
    private static final String KETTLE_POUR = "farmersrespite:kettle_pouring";
    private static final String KEG = "brewinandchewin:fermenting";
    private static final String KEG_POUR = "brewinandchewin:keg_pouring";
    private static final String DOUGH = "farmersdelight:dough";

    private ModdedRecipes() {}

    static List<Recipe<?>> collect(Player player) {
        Level level = player.level();
        RegistryAccess access = level.registryAccess();
        Collection<Recipe<?>> all = level.getRecipeManager().getRecipes();

        // Kettles and kegs brew a fluid, then pour it into a container. Map each fluid to the items it pours into.
        Map<Fluid, List<ItemStack>> pours = new HashMap<>();
        Map<Item, ItemStack> pourContainer = new HashMap<>();
        Map<Fluid, ItemStack> kettlePour = new HashMap<>();
        for (Recipe<?> r : all) {
            String type = typeId(r);
            if (!type.equals(KETTLE_POUR) && !type.equals(KEG_POUR)) continue;
            if (!(field(r, "fluid") instanceof Fluid fluid) || !(call(r, "getContainer") instanceof ItemStack container)) continue;
            ItemStack out = r.getResultItem(access);
            pours.computeIfAbsent(fluid, k -> new ArrayList<>()).add(out);
            pourContainer.putIfAbsent(out.getItem(), container);
            if (type.equals(KETTLE_POUR)) kettlePour.putIfAbsent(fluid, out);
        }

        List<Recipe<?>> recipes = new ArrayList<>();
        for (Recipe<?> r : all) {
            switch (typeId(r)) {
                // Pouring alone would claim a drink needs only an empty bottle; the brewing recipes below cover it.
                case KETTLE_POUR, KEG_POUR -> {}
                case CUISINE -> {
                    NonNullList<Ingredient> needs = cuisineNeeds(r);
                    if (!needs.isEmpty()) recipes.add(new Derived(r.getId(), r.getResultItem(access), needs, "Skillet"));
                }
                case KETTLE -> {
                    if (!(call(r, "getFluidOut") instanceof FluidStack out)) continue;
                    ItemStack drink = kettlePour.get(out.getFluid());
                    if (drink == null) continue;
                    recipes.add(brewed(r, drink, call(r, "getFluidIn"), pours, pourContainer, "Kettle"));
                }
                case KEG -> {
                    ItemStack result = r.getResultItem(access);
                    if (!result.isEmpty()) recipes.add(brewed(r, result, call(r, "getFluidIngredient"), pours, pourContainer, "Keg"));
                }
                case DOUGH -> recipes.add(new Derived(r.getId(), new ItemStack(ModItems.WHEAT_DOUGH.get()),
                        NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.WHEAT), Ingredient.of(Items.WATER_BUCKET)), "Crafting"));
                default -> recipes.add(r);
            }
        }
        addFeasts(recipes);
        addCheeseAging(recipes);
        return recipes;
    }

    private static Derived brewed(Recipe<?> r, ItemStack result, Object baseFluid, Map<Fluid, List<ItemStack>> pours,
                                  Map<Item, ItemStack> pourContainer, String station) {
        NonNullList<Ingredient> needs = NonNullList.create();
        needs.addAll(r.getIngredients());
        // Water is effectively free; any other base (milk, honey, an earlier brew) has to come from an item that pours it in.
        if (baseFluid instanceof FluidStack base && !base.isEmpty() && !base.getFluid().isSame(Fluids.WATER)) {
            List<ItemStack> sources = new ArrayList<>(pours.getOrDefault(base.getFluid(), List.of()));
            if (sources.isEmpty() && base.getFluid().getBucket() != Items.AIR) sources.add(new ItemStack(base.getFluid().getBucket()));
            if (!sources.isEmpty()) needs.add(Ingredient.of(sources.stream()));
        }
        ItemStack container = pourContainer.getOrDefault(result.getItem(), ItemStack.EMPTY);
        if (!container.isEmpty()) needs.add(Ingredient.of(container));
        return new Derived(r.getId(), result.copyWithCount(1), needs, station);
    }

    // Skillet recipes keep their real requirements in a public `list` of match records. Any ratio within each
    // match's range works, so one of each ingredient plus the plate the dish is served on is used as the plan.
    private static NonNullList<Ingredient> cuisineNeeds(Recipe<?> recipe) {
        NonNullList<Ingredient> needs = NonNullList.create();
        if (!(field(recipe, "list") instanceof List<?> list)) return needs;
        for (Object match : list) {
            if (call(match, "ingredient") instanceof Ingredient ing) needs.add(ing);
        }
        Item plate = ForgeRegistries.ITEMS.getValue(new ResourceLocation("cuisinedelight", "plate"));
        if (!needs.isEmpty() && plate != null && plate != Items.AIR) needs.add(Ingredient.of(plate));
        return needs;
    }

    // Feast servings come from right-clicking the placed block, holding the serving's container if it has one.
    private static void addFeasts(List<Recipe<?>> recipes) {
        for (Item item : ForgeRegistries.ITEMS) {
            if (!(item instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof FeastBlock feast)) continue;
            ResourceLocation feastId = ForgeRegistries.ITEMS.getKey(item);
            Set<Item> seen = new HashSet<>();
            for (BlockState state : feast.getStateDefinition().getPossibleStates()) {
                if (state.getValue(feast.getServingsProperty()) == 0) continue;
                ItemStack serving = feast.getServingItem(state);
                if (serving.isEmpty() || !seen.add(serving.getItem())) continue;
                NonNullList<Ingredient> needs = NonNullList.create();
                needs.add(Ingredient.of(item));
                ItemStack container = serving.getCraftingRemainingItem();
                if (!container.isEmpty()) needs.add(Ingredient.of(container));
                ResourceLocation id = new ResourceLocation(feastId.getNamespace(),
                        feastId.getPath() + "/" + ForgeRegistries.ITEMS.getKey(serving.getItem()).getPath());
                recipes.add(new Derived(id, serving.copyWithCount(1), needs, "Feast"));
            }
        }
    }

    // Brewin' and Chewin' cheese is poured as an unripe wheel that ripens over time once placed.
    private static void addCheeseAging(List<Recipe<?>> recipes) {
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (!id.getNamespace().equals("brewinandchewin") || !id.getPath().startsWith("unripe_")) continue;
            Item ripe = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id.getNamespace(), id.getPath().substring("unripe_".length())));
            if (ripe == null || ripe == Items.AIR) continue;
            recipes.add(new Derived(id.withSuffix("/aging"), new ItemStack(ripe), NonNullList.of(Ingredient.EMPTY, Ingredient.of(item)), "Aging"));
        }
    }

    private static String typeId(Recipe<?> recipe) {
        ResourceLocation id = recipe.getType() == null ? null : ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
        return id == null ? "" : id.toString();
    }

    private static Object call(Object o, String method) {
        try {
            return o.getClass().getMethod(method).invoke(o);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
        return null;
    }
}
