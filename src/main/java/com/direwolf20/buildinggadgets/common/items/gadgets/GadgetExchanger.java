package com.direwolf20.buildinggadgets.common.items.gadgets;

import com.direwolf20.buildinggadgets.common.BuildingObjects;
import com.direwolf20.buildinggadgets.common.config.SyncedConfig;
import com.direwolf20.buildinggadgets.common.entities.BlockBuildEntity;
import com.direwolf20.buildinggadgets.common.items.capability.CapabilityProviderEnergy;
import com.direwolf20.buildinggadgets.common.tools.ExchangingModes;
import com.direwolf20.buildinggadgets.common.tools.InventoryManipulation;
import com.direwolf20.buildinggadgets.common.utils.VectorUtil;
import com.direwolf20.buildinggadgets.common.world.FakeBuilderWorld;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.direwolf20.buildinggadgets.common.utils.GadgetUtils.*;

public class GadgetExchanger extends GadgetGeneric {
    private static final FakeBuilderWorld fakeWorld = new FakeBuilderWorld();

    public enum ToolMode {
        Wall, VerticalColumn, HorizontalColumn, Checkerboard;
        private static ToolMode[] vals = values();//TODO unused

        public ToolMode next() {//TODO unused
            return vals[(this.ordinal() + 1) % vals.length];
        }
    }

    public GadgetExchanger(Builder builder) {
        super(builder);

        if (!SyncedConfig.poweredByFE) {
            builder.defaultMaxDamage(SyncedConfig.durabilityExchanger);
        }
    }

    @Override
    public int getEnergyMax() {
        return 0;
    }

    @Override
    public int getEnergyCost() {
        return 0;
    }

    @Override
    public int getItemEnchantability() {
        return 3;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return true;
    }

    @Override
    public boolean isBookEnchantable(ItemStack stack, ItemStack book) {
        if (EnchantmentHelper.getEnchantments(book).containsKey(Enchantments.SILK_TOUCH)) {
            return true;
        }
        return super.isBookEnchantable(stack, book);
    }

    @Override
    public boolean canApplyAtEnchantingTable(ItemStack stack, net.minecraft.enchantment.Enchantment enchantment) {
        if (enchantment == Enchantments.SILK_TOUCH) {
            return true;
        }
        return super.canApplyAtEnchantingTable(stack, enchantment);
    }

    private static void setFuzzy(ItemStack stack, boolean fuzzy) {
        NBTTagCompound tagCompound = stack.getTag();
        if (tagCompound == null) {
            tagCompound = new NBTTagCompound();
        }
        tagCompound.setBoolean("fuzzy", fuzzy);
    }

    public static boolean getFuzzy(ItemStack stack) {
        NBTTagCompound tagCompound = stack.getTag();
        if (tagCompound == null) {
            tagCompound = new NBTTagCompound();
        }
        return tagCompound.getBoolean("fuzzy");
    }

    private static void setToolMode(ItemStack stack, ToolMode mode) {
        NBTTagCompound tagCompound = stack.getTag();
        if (tagCompound == null) {
            tagCompound = new NBTTagCompound();
        }
        tagCompound.setString("mode", mode.name());
        stack.setTag(tagCompound);
    }

    public static ToolMode getToolMode(ItemStack stack) {
        NBTTagCompound tagCompound = stack.getTag();
        ToolMode mode = ToolMode.Wall;
        if (tagCompound == null) {
            setToolMode(stack, mode);
            return mode;
        }
        try {
            mode = ToolMode.valueOf(tagCompound.getString("mode"));
        } catch (Exception e) {
            setToolMode(stack, mode);
        }
        return mode;
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<ITextComponent> tooltip, ITooltipFlag flagIn) {
        super.addInformation(stack, worldIn, tooltip, flagIn);

        tooltip.add(new TextComponentString(TextFormatting.DARK_GREEN + I18n.format("tooltip.gadget.block") + ": " + getToolBlock(stack).getBlock().getNameTextComponent()));
        tooltip.add(new TextComponentString(TextFormatting.AQUA + I18n.format("tooltip.gadget.mode") + ": " + getToolMode(stack)));
        tooltip.add(new TextComponentString(TextFormatting.RED + I18n.format("tooltip.gadget.range") + ": " + getToolRange(stack)));
        if (SyncedConfig.poweredByFE) {
            CapabilityProviderEnergy.getCap(stack).ifPresent(iEnergyStorage -> tooltip.add(new TextComponentString(TextFormatting.WHITE + I18n.format("tooltip.gadget.energy") + ": " + withSuffix(iEnergyStorage.getEnergyStored()) + "/" + withSuffix(iEnergyStorage.getMaxEnergyStored()))));
        }
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack itemstack = player.getHeldItem(hand);
        player.setActiveHand(hand);
        if (!world.isRemote) {
            if (player.isSneaking()) {
                selectBlock(itemstack, player);
            } else {
                exchange(player, itemstack);
            }
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, itemstack);
    }

    public void toggleMode(EntityPlayer player, ItemStack heldItem) {//TODO unused
        ToolMode mode = getToolMode(heldItem);
        mode = mode.next();
        setToolMode(heldItem, mode);
        player.sendStatusMessage(new TextComponentString(TextFormatting.AQUA + new TextComponentTranslation("message.gadget.toolmode").getUnformattedComponentText() + ": " + mode.name()), true);
    }

    public void setMode(EntityPlayer player, ItemStack heldItem, int modeInt) {
        //Called when we specify a mode with the radial menu
        ToolMode mode = ToolMode.values()[modeInt];
        setToolMode(heldItem, mode);
        player.sendStatusMessage(new TextComponentString(TextFormatting.AQUA + new TextComponentTranslation("message.gadget.toolmode").getUnformattedComponentText() + ": " + mode.name()), true);
    }

    public void toggleFuzzy(EntityPlayer player, ItemStack heldItem) {
        setFuzzy(heldItem, !(getFuzzy(heldItem)));
        player.sendStatusMessage(new TextComponentString(TextFormatting.AQUA + new TextComponentTranslation("message.gadget.fuzzymode").getUnformattedComponentText() + ": " + getFuzzy(heldItem)), true);
    }

    public void rangeChange(EntityPlayer player, ItemStack heldItem) {
        int range = getToolRange(heldItem);
        int changeAmount = (getToolMode(heldItem) == ToolMode.Checkerboard || (range % 2 == 0)) ? 1 : 2;
        if (player.isSneaking()) {
            range = (range <= 1) ? SyncedConfig.maxRange : range - changeAmount;
        } else {
            range = (range >= SyncedConfig.maxRange) ? 1 : range + changeAmount;
        }
        setToolRange(heldItem, range);
        player.sendStatusMessage(new TextComponentString(TextFormatting.DARK_AQUA + new TextComponentTranslation("message.gadget.toolrange").getUnformattedComponentText() + ": " + range), true);
    }

    private boolean exchange(EntityPlayer player, ItemStack stack) {
        World world = player.world;
        List<BlockPos> coords = getAnchor(stack);

        if (coords.size() == 0) { //If we don't have an anchor, build in the current spot
            RayTraceResult lookingAt = VectorUtil.getLookingAt(player);
            if (lookingAt == null) { //If we aren't looking at anything, exit
                return false;
            }
            BlockPos startBlock = lookingAt.getBlockPos();
            EnumFacing sideHit = lookingAt.sideHit;
//            IBlockState setBlock = getToolBlock(stack);
            coords = ExchangingModes.getBuildOrders(world, player, startBlock, sideHit, stack);
        } else { //If we do have an anchor, erase it (Even if the build fails)
            setAnchor(stack, new ArrayList<BlockPos>());
        }
        Set<BlockPos> coordinates = new HashSet<BlockPos>(coords);

        ItemStack heldItem = getGadget(player);
        if (heldItem.isEmpty())
            return false;

        IBlockState blockState = getToolBlock(heldItem);

        if (blockState != Blocks.AIR.getDefaultState()) {  //Don't attempt a build if a block is not chosen -- Typically only happens on a new tool.
            IBlockState state = Blocks.AIR.getDefaultState(); //Initialize a new State Variable for use in the fake world
            fakeWorld.setWorldAndState(player.world, blockState, coordinates); // Initialize the fake world's blocks
            for (BlockPos coordinate : coords) {
                if (fakeWorld.getWorldType() != WorldType.DEBUG_ALL_BLOCK_STATES) {
                    try {
                        state = blockState.getExtendedState(world/*fakework*/, coordinate);  //Get the state of the block in the fake world (This lets fences be connected, etc)
                    } catch (Exception var8) {
                    }
                }
                //Get the extended block state in the fake world
                //Disabled to fix Chisel
                //state = state.getBlock().getExtendedState(state, fakeWorld, coordinate);
                exchangeBlock(world, player, coordinate, state);
            }
        }
        return true;
    }

    private boolean exchangeBlock(World world, EntityPlayer player, BlockPos pos, IBlockState setBlock) {
        IBlockState currentBlock = world.getBlockState(pos);
        ItemStack itemStack;
        boolean useConstructionPaste = false;
        //ItemStack itemStack = setBlock.getBlock().getPickBlock(setBlock, null, world, pos, player);
        if (setBlock.getBlock().canSilkHarvest(setBlock, world, pos, player)) {
            itemStack = InventoryManipulation.getSilkTouchDrop(setBlock);
        } else {
            itemStack = setBlock.getBlock().getPickBlock(setBlock, null, world, pos, player);
        }
        if (itemStack.getItem().equals(Items.AIR)) {
            itemStack = setBlock.getBlock().getPickBlock(setBlock, null, world, pos, player);
        }

        ItemStack tool = getGadget(player);
        if (tool.isEmpty())
            return false;

        NonNullList<ItemStack> drops = NonNullList.create();
        setBlock.getBlock().getDrops(setBlock, drops, world, pos, 0);
        int neededItems = 0;
        for (ItemStack drop : drops) {
            if (drop.getItem().equals(itemStack.getItem())) {
                neededItems++;
            }
        }
        if (neededItems == 0) {
            neededItems = 1;
        }
        if (InventoryManipulation.countItem(itemStack, player, world) < neededItems) {
            ItemStack constructionPaste = new ItemStack(BuildingObjects.constructionPaste);
            if (InventoryManipulation.countPaste(player) < neededItems) {
                return false;
            }
            itemStack = constructionPaste.copy();
            useConstructionPaste = true;
        }
        if (!player.isAllowEdit()) {
            return false;
        }
        if (!world.isBlockModifiable(player, pos)) {
            return false;
        }
        BlockSnapshot blockSnapshot = BlockSnapshot.getBlockSnapshot(world, pos);
        if (ForgeEventFactory.onPlayerBlockPlace(player, blockSnapshot, EnumFacing.UP, EnumHand.MAIN_HAND).isCanceled()) {
            return false;
        }
        BlockEvent.BreakEvent e = new BlockEvent.BreakEvent(world, pos, currentBlock, player);
        if (MinecraftForge.EVENT_BUS.post(e)) {
            return false;
        }

        if( !this.canUse(tool, player) )
            return false;

        this.applyDamage(tool, player);

        currentBlock.getBlock().harvestBlock(world, player, pos, currentBlock, world.getTileEntity(pos), tool);
        boolean useItemSuccess;
        if (useConstructionPaste) {
            useItemSuccess = InventoryManipulation.usePaste(player, 1);
        } else {
            useItemSuccess = InventoryManipulation.useItem(itemStack, player, neededItems, world);
        }
        if (useItemSuccess) {
            world.spawnEntity(new BlockBuildEntity(world, pos, player, setBlock, 3, getToolActualBlock(tool), useConstructionPaste));
            return true;
        }
        return false;
    }

    public static ItemStack getGadget(EntityPlayer player) {
        ItemStack stack = GadgetGeneric.getGadget(player);
        if (!(stack.getItem() instanceof GadgetExchanger))
            return ItemStack.EMPTY;

        return stack;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 20;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public boolean hasEffect(ItemStack stack) {
        return false;
    }

}
