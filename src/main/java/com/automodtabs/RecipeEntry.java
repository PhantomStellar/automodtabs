package com.automodtabs;

import java.util.List;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * One discovered recipe output.
 *
 * <p>{@link #stack} is the <b>complete</b> output stack resolved through the
 * recipe display API - item type, data components, custom data, custom name -
 * with the count normalised to 1 for creative-tab display (the original count
 * is kept in {@link #count}); components of the vanilla
 * "the same item with different data" case are therefore never lost.
 *
 * <p>{@link #source} is the namespace of the <i>recipe id</i>, which is the
 * only reliable source identifier: a datapack or mod recipe can turn an
 * existing item (even a vanilla one) into a customized variant, so the output
 * item's registry namespace says nothing about who supplied it. E.g. the
 * Pocket Portals datapack registers recipes under {@code dqc.portal} but its
 * results are customized {@code minecraft:painting} stacks.
 *
 * <p>{@link #customized} is true when the output's data components differ from
 * the underlying item's default instance - such variants are kept as distinct
 * entries in the recipe-source tab.
 *
 * @param source     namespace of the recipe id (datapack or mod)
 * @param recipeId   full id of the recipe that produced the stack
 * @param item       the underlying registry item
 * @param stack      display copy, count normalised to 1, full components
 * @param count      original output count of the recipe
 * @param customized whether the components differ from the item default
 */
public record RecipeEntry(String source, Identifier recipeId, Item item, ItemStack stack, int count, boolean customized) {

    /**
     * Two stacks represent the same output variant when the item and the
     * complete data components match (count is irrelevant). Never merge
     * different customized variants.
     */
    public boolean sameVariant(ItemStack other) {
        return stack.is(other.getItem()) && stack.getComponents().equals(other.getComponents());
    }

    /** Same-variant check against another entry. */
    public boolean sameVariant(RecipeEntry other) {
        return sameVariant(other.stack);
    }
}
