package dev.seria.foodplanner;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class ContainerMemory {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int CLICK_TO_OPEN_WINDOW_TICKS = 40;
    private static final int SCAN_TIMEOUT_TICKS = 30;
    private static final int SCAN_SETTLE_TICKS = 2;

    record Key(ResourceKey<Level> dim, BlockPos pos) {}

    private static final Map<Key, List<ItemStack>> remembered = new HashMap<>();
    private static File saveFile;

    private static Key lastClicked;
    private static long lastClickTime;
    private static Key openContainer;

    private static final Deque<Key> scanQueue = new ArrayDeque<>();
    private static Key scanTarget;
    private static AbstractContainerMenu scanMenu;
    private static int scanTicks;
    private static int restoreSlot = -1;
    private static int version;

    private ContainerMemory() {}

    static int version() {
        return version;
    }

    static boolean isScanning() {
        return scanTarget != null || !scanQueue.isEmpty();
    }

    static int scanRemaining() {
        return scanQueue.size() + (scanTarget != null ? 1 : 0);
    }

    record Nearby(List<ItemStack> items, int containers) {}

    static Nearby nearby(Player player, double radius) {
        Level level = player.level();
        List<ItemStack> items = new ArrayList<>();
        int containers = 0;
        Iterator<Map.Entry<Key, List<ItemStack>>> it = remembered.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, List<ItemStack>> e = it.next();
            Key key = e.getKey();
            if (!key.dim().equals(level.dimension()) || !key.pos().closerToCenterThan(player.position(), radius)) continue;
            if (level.isLoaded(key.pos()) && level.getBlockEntity(key.pos()) == null) {
                it.remove();
                continue;
            }
            containers++;
            for (ItemStack s : e.getValue()) items.add(s.copy());
        }
        return new Nearby(items, containers);
    }

    private static Key keyFor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (other.asLong() < pos.asLong()) pos = other;
        }
        return new Key(level.dimension(), pos.immutable());
    }

    private static List<ItemStack> snapshot(AbstractContainerMenu menu, Inventory playerInv) {
        List<ItemStack> out = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container != playerInv && slot.hasItem()) out.add(slot.getItem().copy());
        }
        return out;
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide() || event.getLevel().getBlockEntity(event.getPos()) == null) return;
        lastClicked = keyFor(event.getLevel(), event.getPos());
        lastClickTime = event.getLevel().getGameTime();
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof AbstractContainerScreen<?>)) return;
        Minecraft mc = Minecraft.getInstance();
        if (scanTarget != null && scanMenu == null && mc.player != null) {
            // The server answered our scan click; keep the menu but never show its screen.
            scanMenu = mc.player.containerMenu;
            scanTicks = 0;
            event.setCanceled(true);
            return;
        }
        if (lastClicked != null && mc.level != null && mc.level.getGameTime() - lastClickTime <= CLICK_TO_OPEN_WINDOW_TICKS) {
            openContainer = lastClicked;
        }
        lastClicked = null;
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (openContainer == null || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            remembered.put(openContainer, snapshot(screen.getMenu(), player.getInventory()));
            version++;
            save();
        }
        openContainer = null;
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        remembered.clear();
        saveFile = resolveSaveFile();
        load();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        save();
        remembered.clear();
        scanQueue.clear();
        scanTarget = null;
        scanMenu = null;
        restoreSlot = -1;
        saveFile = null;
    }

    /** Returns an error message, or null if the scan started. */
    static String startScan() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || isScanning()) return null;

        double reach = player.getBlockReach();
        Vec3 eye = player.getEyePosition();
        int r = (int) Math.ceil(reach) + 1;
        Set<Key> seen = new HashSet<>();
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(player.blockPosition().offset(-r, -r, -r), player.blockPosition().offset(r, r + 1, r))) {
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > reach * reach) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || !isScannable(be)) continue;
            if (seen.add(keyFor(level, pos))) targets.add(pos.immutable());
        }
        if (targets.isEmpty()) return "No containers within reach to scan.";

        Inventory inv = player.getInventory();
        if (!inv.getSelected().isEmpty()) {
            // An empty hand guarantees the click opens the container instead of using/placing the held item.
            int empty = -1;
            for (int i = 0; i < Inventory.getSelectionSize(); i++) {
                if (inv.getItem(i).isEmpty()) {
                    empty = i;
                    break;
                }
            }
            if (empty < 0) return "Free up a hotbar slot so the scan can click with an empty hand.";
            restoreSlot = inv.selected;
            selectSlot(player, empty);
        }

        targets.sort(Comparator.comparingDouble(p -> eye.distanceToSqr(Vec3.atCenterOf(p))));
        for (BlockPos pos : targets) scanQueue.add(new Key(level.dimension(), pos));
        return null;
    }

    private static boolean isScannable(BlockEntity be) {
        if (be instanceof MenuProvider) return true;
        // Sophisticated Storage blocks open a GUI but aren't MenuProviders. Limited barrels are skipped:
        // clicking their front face moves items instead of opening a GUI.
        ResourceLocation id = BlockEntityType.getKey(be.getType());
        return id != null && id.getNamespace().equals("sophisticatedstorage") && !id.getPath().contains("limited");
    }

    private static void selectSlot(LocalPlayer player, int slot) {
        player.getInventory().selected = slot;
        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isScanning()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;

        if (scanTarget == null) {
            scanTarget = scanQueue.poll();
            scanMenu = null;
            scanTicks = 0;
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(scanTarget.pos()), Direction.UP, scanTarget.pos(), false);
            // Sequence 0: no client-side block prediction was made, so there's nothing for the server to acknowledge.
            player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, 0));
            return;
        }

        scanTicks++;
        if (scanMenu != null && scanTicks >= SCAN_SETTLE_TICKS) {
            remembered.put(keyFor(level, scanTarget.pos()), snapshot(scanMenu, player.getInventory()));
            player.connection.send(new ServerboundContainerClosePacket(scanMenu.containerId));
            if (player.containerMenu == scanMenu) player.containerMenu = player.inventoryMenu;
            scanTarget = null;
        } else if (scanMenu == null && scanTicks > SCAN_TIMEOUT_TICKS) {
            scanTarget = null;
        }

        if (!isScanning()) {
            if (restoreSlot >= 0) selectSlot(player, restoreSlot);
            restoreSlot = -1;
            version++;
            save();
        }
    }

    private static File resolveSaveFile() {
        Minecraft mc = Minecraft.getInstance();
        String id;
        if (mc.getCurrentServer() != null) id = mc.getCurrentServer().ip;
        else if (mc.getSingleplayerServer() != null) id = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else id = "unknown";
        File dir = FMLPaths.GAMEDIR.get().resolve(FoodPlanner.MOD_ID).toFile();
        return new File(dir, id.replaceAll("[^a-zA-Z0-9._-]", "_") + ".dat");
    }

    private static void save() {
        if (saveFile == null) return;
        ListTag list = new ListTag();
        for (Map.Entry<Key, List<ItemStack>> e : remembered.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("dim", e.getKey().dim().location().toString());
            c.putLong("pos", e.getKey().pos().asLong());
            ListTag items = new ListTag();
            for (ItemStack s : e.getValue()) {
                CompoundTag t = s.save(new CompoundTag());
                t.putInt("FullCount", s.getCount()); // vanilla Count is a byte; Sophisticated Storage slots go higher
                items.add(t);
            }
            c.put("items", items);
            list.add(c);
        }
        CompoundTag root = new CompoundTag();
        root.put("containers", list);
        try {
            saveFile.getParentFile().mkdirs();
            NbtIo.writeCompressed(root, saveFile);
        } catch (IOException ex) {
            LOG.error("Failed to save container memory to {}", saveFile, ex);
        }
    }

    private static void load() {
        if (saveFile == null || !saveFile.exists()) return;
        try {
            CompoundTag root = NbtIo.readCompressed(saveFile);
            for (Tag t : root.getList("containers", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(c.getString("dim")));
                List<ItemStack> items = new ArrayList<>();
                for (Tag it : c.getList("items", Tag.TAG_COMPOUND)) {
                    CompoundTag ct = (CompoundTag) it;
                    ItemStack s = ItemStack.of(ct);
                    if (s.isEmpty()) continue;
                    s.setCount(ct.getInt("FullCount"));
                    items.add(s);
                }
                remembered.put(new Key(dim, BlockPos.of(c.getLong("pos"))), items);
            }
        } catch (IOException ex) {
            LOG.error("Failed to load container memory from {}", saveFile, ex);
        }
    }
}
