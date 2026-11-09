package me.orbital.strike;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class OrbitalStrike extends JavaPlugin implements Listener {

    private NamespacedKey rodKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        rodKey = new NamespacedKey(this, "orbital_strike_rod");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("OrbitalStrike enabled.");
    }

    // ---------- /osc ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("osc")) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!player.isOp() && !player.hasPermission("orbitalstrike.give")) {
            player.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }

        player.getInventory().addItem(createRod()).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.sendMessage(Component.text("You received the Orbital Strike rod. Right click to fire!",
                NamedTextColor.GOLD));
        return true;
    }

    private ItemStack createRod() {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();
        meta.displayName(Component.text("Orbital Strike", NamedTextColor.RED, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right click to call down", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("an orbital TNT strike.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(rodKey, PersistentDataType.BYTE, (byte) 1);
        rod.setItemMeta(meta);
        return rod;
    }

    private boolean isRod(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(rodKey, PersistentDataType.BYTE);
    }

    // ---------- Firing ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!isRod(player.getInventory().getItemInMainHand())) {
            return;
        }

        // Stop the rod from casting a bobber / interacting with blocks.
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);

       
        long now = System.currentTimeMillis();
        long cooldownMs = (long) (getConfig().getDouble("cooldown-seconds", 3.0) * 1000);
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) {
            double left = (cooldownMs - (now - last)) / 1000.0;
            player.sendActionBar(Component.text(String.format("Recharging... %.1fs", left), NamedTextColor.YELLOW));
            return;
        }
        cooldowns.put(player.getUniqueId(), now);

        fire(player);
    }

    // Safety net: never let this rod cast a real fishing bobber.
    @EventHandler(priority = EventPriority.HIGH)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        if (isRod(player.getInventory().getItemInMainHand())
                || isRod(player.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    private void fire(Player player) {
        double range = getConfig().getDouble("max-range", 200.0);
        World world = player.getWorld();

        Location target;
        RayTraceResult hit = player.rayTraceBlocks(range);
        if (hit != null) {
            target = hit.getHitPosition().toLocation(world);
        } else {
            target = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(range));
        }

        final int count = Math.max(1, getConfig().getInt("tnt-count", 300));
        final double radius = Math.max(0.0, getConfig().getDouble("radius", 8.0));
        final int height = Math.max(5, getConfig().getInt("height", 60));
        final int fuse = Math.max(20, getConfig().getInt("fuse-ticks", 70));
        final int ticks = Math.max(1, getConfig().getInt("spawn-over-ticks", 20));

        final double spawnY = Math.min(target.getY() + height, world.getMaxHeight() - 2);
        final double centerX = target.getX();
        final double centerZ = target.getZ();

        player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.5f);
        player.sendActionBar(Component.text("ORBITAL STRIKE INBOUND", NamedTextColor.RED, TextDecoration.BOLD));

        // Spawned ONCE in total, spread over a few ticks so the server doesn't spike.
        final int perTick = (int) Math.ceil(count / (double) ticks);

        new BukkitRunnable() {
            int spawned = 0;

            @Override
            public void run() {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                int batch = Math.min(perTick, count - spawned);
                for (int i = 0; i < batch; i++) {
                    // Random point inside a circle.
                    double angle = rnd.nextDouble(0, Math.PI * 2);
                    double dist = Math.sqrt(rnd.nextDouble()) * radius;
                    Location loc = new Location(world,
                            centerX + Math.cos(angle) * dist,
                            spawnY + rnd.nextDouble(0, 6),
                            centerZ + Math.sin(angle) * dist);

                    world.spawn(loc, TNTPrimed.class, tnt -> {
                        tnt.setFuseTicks(fuse);
                        tnt.setSource(player);
                        tnt.setVelocity(new Vector(0, -1.0, 0));
                    });
                }
                spawned += batch;
                if (spawned >= count) {
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }
}
