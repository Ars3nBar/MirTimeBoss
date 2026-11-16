/*
 *    __  __ _        _____ _                
 *   |  \/  (_)_ __  |_   _(_)_ __ ___   ___ 
 *   | |\/| | | '__|   | | | | '_ ` _ \ / _ \
 *   | |  | | | |      | | | | | | | | |  __/
 *   |_|  |_|_|_|      |_| |_|_| |_| |_|\___|
 *                                           
 *   Project: Mir Time | Author: Ars3nBar
 */
package ru.mirtime.boss;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MirTimeBoss extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private LivingEntity activeBoss;
    private String activeBossKey;
    private BossBar bossBar;
    private long lastDamageTime;
    private int rotationIndex = 0;
    private int abilityTimer = 0;
    private final Map<UUID, Double> damageMap = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getCommand("boss") != null) {
            getCommand("boss").setExecutor(this);
            getCommand("boss").setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        long intervalTicks = getConfig().getInt("spawn-interval-minutes", 60) * 60L * 20L;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (activeBoss != null && activeBoss.isValid() && !activeBoss.isDead()) return;
                int minOnline = getConfig().getInt("min-online", 2);
                if (Bukkit.getOnlinePlayers().size() < minOnline) return;
                spawnNextBoss(null);
            }
        }.runTaskTimer(this, intervalTicks, intervalTicks);

        new BukkitRunnable() {
            @Override
            public void run() {
                tickBoss();
            }
        }.runTaskTimer(this, 20L, 20L);

        Bukkit.getConsoleSender().sendMessage(color("&#0055ff   __  __ _        _____ _                "));
        Bukkit.getConsoleSender().sendMessage(color("&#0055ff  |  \\/  (_)_ __  |_   _(_)_ __ ___   ___ "));
        Bukkit.getConsoleSender().sendMessage(color("&#00E5FF  | |\\/| | | '__|   | | | | '_ ` _ \\ / _ \\"));
        Bukkit.getConsoleSender().sendMessage(color("&#00E5FF  | |  | | | |      | | | | | | | | |  __/"));
        Bukkit.getConsoleSender().sendMessage(color("&#00FF66  |_|  |_|_|_|      |_| |_|_| |_| |_|\\___|"));
        Bukkit.getConsoleSender().sendMessage(color("&#00E5FF  MirTimeBoss v1.0.0 успешно запущен!"));
    }

    @Override
    public void onDisable() {
        cleanupBoss();
    }

    private void cleanupBoss() {
        if (activeBoss != null && activeBoss.isValid()) activeBoss.remove();
        activeBoss = null;
        activeBossKey = null;
        damageMap.clear();
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String prefix = color(getConfig().getString("prefix", "&#0055ff&l⚡ &#00E5FF&lMIR-TIME &8» "));
        if (args.length == 0) {
            sender.sendMessage(prefix + color("&fКоманды: &#00FF66/boss setspawn &7| &#00E5FF/boss start [босс] &7| &c/boss stop &7| &f/boss reload"));
            return true;
        }
        String sub = List.of(args).get(0).toLowerCase();
        switch (sub) {
            case "setspawn" -> {
                if (!(sender instanceof Player p)) return true;
                Location l = p.getLocation();
                getConfig().set("spawn.world", l.getWorld().getName());
                getConfig().set("spawn.x", Math.round(l.getX() * 10.0) / 10.0);
                getConfig().set("spawn.y", Math.round(l.getY() * 10.0) / 10.0);
                getConfig().set("spawn.z", Math.round(l.getZ() * 10.0) / 10.0);
                saveConfig();
                sender.sendMessage(prefix + color("&#00FF66Точка спавна боссов сохранена в мире &f" + l.getWorld().getName() + "&#00FF66!"));
            }
            case "start" -> {
                String key = (args.length >= 2) ? List.of(args).get(1).toLowerCase() : null;
                if (!spawnNextBoss(key)) {
                    sender.sendMessage(prefix + color("&cМир не найден или точка не задана! Встаньте на арену и напишите &#00FF66/boss setspawn"));
                }
            }
            case "stop" -> {
                cleanupBoss();
                sender.sendMessage(prefix + color("&cТекущий босс удалён с арены."));
            }
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(prefix + color("&#00FF66Конфиг MirTimeBoss перезагружен!"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("setspawn", "start", "stop", "reload");
        if (args.length == 2 && List.of(args).get(0).equalsIgnoreCase("start")) {
            ConfigurationSection sec = getConfig().getConfigurationSection("bosses");
            return (sec != null) ? new ArrayList<>(sec.getKeys(false)) : List.of();
        }
        return List.of();
    }
    private boolean spawnNextBoss(String forcedKey) {
        String worldName = getConfig().getString("spawn.world", "game");
        if (worldName == null || Bukkit.getWorld(worldName) == null) return false;
        Location loc = new Location(
                Bukkit.getWorld(worldName),
                getConfig().getDouble("spawn.x"),
                getConfig().getDouble("spawn.y"),
                getConfig().getDouble("spawn.z")
        );

        ConfigurationSection sec = getConfig().getConfigurationSection("bosses");
        if (sec == null) return false;
        List<String> keys = new ArrayList<>(sec.getKeys(false));
        if (keys.isEmpty()) return false;

        String key = (forcedKey != null && keys.contains(forcedKey)) ? forcedKey : keys.get(rotationIndex % keys.size());
        rotationIndex++;

        cleanupBoss();

        String path = "bosses." + key;
        String bossName = color(getConfig().getString(path + ".name", "&c&lБосс Арены"));
        EntityType type;
        try {
            type = EntityType.valueOf(getConfig().getString(path + ".type", "RAVAGER").toUpperCase());
        } catch (Exception e) {
            type = EntityType.RAVAGER;
        }
        double maxHp = getConfig().getDouble(path + ".health", 1500.0);
        double dmg = getConfig().getDouble(path + ".damage", 18.0);

        LivingEntity ent = (LivingEntity) loc.getWorld().spawnEntity(loc, type);
        ent.setCustomName(bossName);
        ent.setCustomNameVisible(true);
        ent.setRemoveWhenFarAway(false);
        if (ent.getAttribute(Attribute.MAX_HEALTH) != null) ent.getAttribute(Attribute.MAX_HEALTH).setBaseValue(maxHp);
        if (ent.getAttribute(Attribute.ATTACK_DAMAGE) != null) ent.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(dmg);
        ent.setHealth(maxHp);

        if (ent instanceof PiglinAbstract piglin) piglin.setImmuneToZombification(true);

        activeBoss = ent;
        activeBossKey = key;
        lastDamageTime = System.currentTimeMillis();

        BarColor bColor;
        try {
            bColor = BarColor.valueOf(getConfig().getString(path + ".bar-color", "RED").toUpperCase());
        } catch (Exception e) {
            bColor = BarColor.RED;
        }

        bossBar = Bukkit.createBossBar(bossName, bColor, BarStyle.SEGMENTED_20);
        bossBar.setProgress(1.0);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);

        loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_SPAWN, 1.5f, 0.9f);
        Bukkit.broadcastMessage(color("&#0055ff&l&m                                                "));
        Bukkit.broadcastMessage(color(" &#0055ff&l⚡ &#00E5FF&lИВЕНТ АРЕНЫ СМЕРТИ &#0055ff&l⚡"));
        Bukkit.broadcastMessage(color(" &fНа Арене Смерти появился: " + bossName));
        Bukkit.broadcastMessage(color(" &fЗдоровье: &#00FF66" + (int) maxHp + " HP &8| &7Успейте сразиться за награды!"));
        Bukkit.broadcastMessage(color("&#0055ff&l&m                                                "));
        return true;
    }

    private void tickBoss() {
        if (activeBoss == null) return;
        if (!activeBoss.isValid() || activeBoss.isDead()) {
            cleanupBoss();
            return;
        }

        int idleLimit = getConfig().getInt("idle-despawn-seconds", 60);
        long idleSec = (System.currentTimeMillis() - lastDamageTime) / 1000;
        if (idleSec >= idleLimit) {
            String name = activeBoss.getCustomName();
            cleanupBoss();
            Bukkit.broadcastMessage(color(getConfig().getString("prefix") + name + " &7покинул Арену Смерти, так как никто не бросил ему вызов!"));
            return;
        }

        double maxHp = activeBoss.getAttribute(Attribute.MAX_HEALTH) != null ? activeBoss.getAttribute(Attribute.MAX_HEALTH).getValue() : 1000.0;
        double ratio = Math.max(0.0, Math.min(1.0, activeBoss.getHealth() / maxHp));
        if (bossBar != null) {
            bossBar.setProgress(ratio);
            bossBar.setTitle(activeBoss.getCustomName() + color(" &8(&c" + (int) activeBoss.getHealth() + " / " + (int) maxHp + " HP&8)"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
            }
        }

        abilityTimer++;
        if (abilityTimer % 12 == 0) {
            Location loc = activeBoss.getLocation();
            for (Entity near : loc.getWorld().getNearbyEntities(loc, 7, 5, 7)) {
                if (near instanceof Player p && p.getGameMode() == GameMode.SURVIVAL) {
                    switch (activeBossKey) {
                        case "spider" -> {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1));
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1));
                        }
                        case "necromancer" -> loc.getWorld().spawnEntity(loc, EntityType.SKELETON);
                        case "ravager" -> p.setVelocity(new Vector(0, 0.9, 0));
                        case "berserk" -> p.setFireTicks(80);
                        case "warden" -> p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0));
                    }
                }
            }
        }
    }

    @EventHandler
    public void onBossDamage(EntityDamageByEntityEvent e) {
        if (activeBoss == null || !e.getEntity().getUniqueId().equals(activeBoss.getUniqueId())) return;
        lastDamageTime = System.currentTimeMillis();
        if (e.getDamager() instanceof Player p) {
            damageMap.put(p.getUniqueId(), damageMap.getOrDefault(p.getUniqueId(), 0.0) + e.getFinalDamage());
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent e) {
        if (activeBoss == null || !e.getEntity().getUniqueId().equals(activeBoss.getUniqueId())) return;
        String bossName = activeBoss.getCustomName();
        String key = activeBossKey;

        List<Map.Entry<UUID, Double>> top = new ArrayList<>(damageMap.entrySet());
        top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        Bukkit.broadcastMessage(color("&#0055ff&l&m                                                "));
        Bukkit.broadcastMessage(color(" &#00FF66&l🏆 БОСС АРЕНЫ СМЕРТИ ПОВЕРЖЕН!"));
        Bukkit.broadcastMessage(color(" &fПобеждён: " + bossName));
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            Player winner = Bukkit.getPlayer(top.get(i).getKey());
            if (winner != null) {
                Bukkit.broadcastMessage(color(" &#00E5FF#" + (i + 1) + " &f" + winner.getName() + " &8(&c" + top.get(i).getValue().intValue() + " урона&8)"));
                for (String cmd : getConfig().getStringList("bosses." + key + ".rewards.top" + (i + 1))) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", winner.getName()));
                }
            }
        }
        Bukkit.broadcastMessage(color("&#0055ff&l&m                                                "));
        cleanupBoss();
    }

    @EventHandler
    public void onBlockChange(EntityChangeBlockEvent e) {
        if (activeBoss != null && e.getEntity().getUniqueId().equals(activeBoss.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        if (activeBoss != null && e.getEntity().getUniqueId().equals(activeBoss.getUniqueId())) e.blockList().clear();
    }

    public static String color(String text) {
        if (text == null) return "";
        Pattern pattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder rep = new StringBuilder("§x");
            for (char c : hex.toCharArray()) rep.append('§').append(c);
            matcher.appendReplacement(buffer, rep.toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
}
