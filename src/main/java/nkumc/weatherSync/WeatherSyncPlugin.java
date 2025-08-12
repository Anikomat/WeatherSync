package nkumc.weatherSync;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.GameRule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Scanner;
import org.json.JSONArray;
import org.json.JSONObject;

public class WeatherSyncPlugin extends JavaPlugin implements Listener {
    private String lastWeather = "";

    // 配置缓存
    private String apiKey;
    private double lat;
    private double lon;
    private int syncIntervalMinutes;
    private int forecastCount;
    private String worldName;

    @Override
    public void onEnable() {
        // 自动生成 config.yml
        saveDefaultConfig();
        reloadLocalConfig();

        // 注册事件监听器
        Bukkit.getPluginManager().registerEvents(this, this);

        // 锁定时间流逝
        World world = getWorldOrDefault();
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);

        // 天气同步任务（异步）
        new BukkitRunnable() {
            @Override
            public void run() {
                syncWeatherAsync();
            }
        }.runTaskTimerAsynchronously(this, 20, 20L * 60 * syncIntervalMinutes);

        // 时间同步任务（主线程，每秒）
        new BukkitRunnable() {
            @Override
            public void run() {
                syncGameTimeToRealWorld();
            }
        }.runTaskTimer(this, 1, 20);

        // 启动时同步一次时间
        syncGameTimeToRealWorld();
    }

    private void reloadLocalConfig() {
        apiKey = getConfig().getString("apikey", "");
        lat = getConfig().getDouble("lat", 39.1171);
        lon = getConfig().getDouble("lon", 117.1806);
        syncIntervalMinutes = getConfig().getInt("sync-interval-minutes", 30);
        forecastCount = getConfig().getInt("forecast-count", 3);
        worldName = getConfig().getString("world", "world");
    }

    private World getWorldOrDefault() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) world = Bukkit.getWorlds().getFirst();
        return world;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        showCurrentAndForecastTitleToPlayerAsync(player);
    }

    /**
     * 异步获取天气并同步到游戏世界
     */
    private void syncWeatherAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String apiUrl = String.format(
                        "https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&appid=%s&lang=zh_cn&units=metric",
                        lat, lon, apiKey
                );
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setRequestMethod("GET");
                Scanner scanner = new Scanner(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder jsonBuilder = new StringBuilder();
                while (scanner.hasNextLine()) {
                    jsonBuilder.append(scanner.nextLine());
                }
                scanner.close();

                JSONObject json = new JSONObject(jsonBuilder.toString());
                String weatherMain = json.getJSONArray("weather").getJSONObject(0).getString("main");
                String weatherDesc = json.getJSONArray("weather").getJSONObject(0).getString("description");
                double tempNow = json.getJSONObject("main").getDouble("temp");

                World world = getWorldOrDefault();

                boolean rain, thunder;
                if (weatherMain.equalsIgnoreCase("Rain") || weatherMain.equalsIgnoreCase("Drizzle")) {
                    thunder = false;
                    rain = true;
                } else if (weatherMain.equalsIgnoreCase("Thunderstorm")) {
                    rain = true;
                    thunder = true;
                } else {
                    rain = false;
                    thunder = false;
                }

                // 回主线程操作世界天气
                Bukkit.getScheduler().runTask(this, () -> {
                    world.setStorm(rain);
                    world.setThundering(thunder);
                });

                String broadcastMsg = "南开大学当前天气：" + weatherDesc + " " + String.format("%.1f°C", tempNow);
                if (!weatherDesc.equals(lastWeather)) {
                    lastWeather = weatherDesc;
                    Bukkit.getScheduler().runTask(this, () -> {
                        Bukkit.broadcastMessage(broadcastMsg);
                    });
                }

                // 异步展示 Title 给所有玩家
                showCurrentAndForecastTitleToAllPlayersAsync(weatherDesc, tempNow);

            } catch (Exception e) {
                getLogger().warning("天气同步失败: " + e.getMessage());
            }
        });
    }

    /**
     * 同步游戏时间到北京时间，锁定并强制设置tick
     */
    private void syncGameTimeToRealWorld() {
        World world = getWorldOrDefault();
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        int hour = now.getHour();
        int minute = now.getMinute();
        int second = now.getSecond();

        // 1小时=1000tick，1分钟≈16.666tick，1秒≈0.277tick
        long tick = ((hour * 1000L) + (minute * 1000L / 60) + (second * 1000L / 3600)) - 6000;
        if (tick < 0) tick += 24000; // 防止负数

        long finalTick = tick;
        Bukkit.getScheduler().runTask(this, () -> {
            world.setTime(finalTick);
        });
    }

    /**
     * 异步展示给所有玩家：先当前天气，后预测天气
     */
    private void showCurrentAndForecastTitleToAllPlayersAsync(final String weatherDesc, final double tempNow) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            showCurrentAndForecastTitleToPlayerAsync(player, weatherDesc, tempNow);
        }
    }

    /**
     * 异步展示给单一玩家：先当前天气，后预测天气
     */
    private void showCurrentAndForecastTitleToPlayerAsync(Player player) {
        // 异步获取当前天气
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String apiUrl = String.format(
                        "https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&appid=%s&lang=zh_cn&units=metric",
                        lat, lon, apiKey
                );
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setRequestMethod("GET");
                Scanner scanner = new Scanner(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder jsonBuilder = new StringBuilder();
                while (scanner.hasNextLine()) {
                    jsonBuilder.append(scanner.nextLine());
                }
                scanner.close();

                JSONObject json = new JSONObject(jsonBuilder.toString());
                String weatherDesc = json.getJSONArray("weather").getJSONObject(0).getString("description");
                double tempNow = json.getJSONObject("main").getDouble("temp");

                showCurrentAndForecastTitleToPlayerAsync(player, weatherDesc, tempNow);

            } catch (Exception e) {
                getLogger().warning("天气获取失败: " + e.getMessage());
            }
        });
    }

    private void showCurrentAndForecastTitleToPlayerAsync(Player player, String weatherDesc, double tempNow) {
        // 获取北京时间
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String beijingTime = String.format("%02d:%02d:%02d", now.getHour(), now.getMinute(), now.getSecond());

        // 拼接副标题：天气、温度、北京时间
        String subtitle = "§d南开大学：" + weatherDesc + " " + String.format("%.1f°C", tempNow) + " | 北京时间: " + beijingTime;

        // 1. 主线程显示当前天气（缩短显示时间：淡入5tick，显示30tick，淡出5tick）
        Bukkit.getScheduler().runTask(this, () -> {
            player.sendTitle("§5当前天气", subtitle, 5, 30, 5);
        });

        // 2. 延时异步获取预测天气（1秒后）
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            try {
                String apiUrl = String.format(
                        "https://api.openweathermap.org/data/2.5/forecast?lat=%f&lon=%f&appid=%s&lang=zh_cn&units=metric",
                        lat, lon, apiKey
                );
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setRequestMethod("GET");
                Scanner scanner = new Scanner(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder jsonBuilder = new StringBuilder();
                while (scanner.hasNextLine()) {
                    jsonBuilder.append(scanner.nextLine());
                }
                scanner.close();

                JSONObject json = new JSONObject(jsonBuilder.toString());
                JSONArray listArr = json.getJSONArray("list");

                StringBuilder titleBuilder = new StringBuilder("§5未来天气预报");
                StringBuilder subtitleBuilder = new StringBuilder("§d");

                for (int i = 0; i < Math.min(forecastCount, listArr.length()); i++) {
                    JSONObject forecast = listArr.getJSONObject(i);
                    String time = getBeijingHourMinute(forecast.getString("dt_txt"));
                    String weatherDescF = forecast.getJSONArray("weather").getJSONObject(0).getString("description");
                    double tempF = forecast.getJSONObject("main").getDouble("temp");
                    subtitleBuilder.append(time)
                            .append(" ").append(weatherDescF)
                            .append(String.format(" %.1f°C", tempF)).append("  ");
                }

                // 回主线程显示预测天气（同样缩短显示时间）
                Bukkit.getScheduler().runTask(this, () -> {
                    player.sendTitle(titleBuilder.toString(), subtitleBuilder.toString(), 5, 40, 5);
                });

            } catch (Exception e) {
                getLogger().warning("天气预测获取失败: " + e.getMessage());
                Bukkit.getScheduler().runTask(this, () -> {
                    player.sendMessage("§c天气预测获取失败，请稍后再试！");
                });
            }
        }, 20); // 1秒后显示预测天气
    }

    private String getBeijingHourMinute(String utcDtTxt) {
        java.time.LocalDateTime utc = java.time.LocalDateTime.parse(utcDtTxt.replace(" ", "T"));
        java.time.ZonedDateTime bj = utc.atZone(java.time.ZoneId.of("UTC")).withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"));
        return String.format("%02d:%02d", bj.getHour(), bj.getMinute());
    }

    // 命令处理
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("weatherreload")) {
            reloadConfig();
            reloadLocalConfig();
            sender.sendMessage("§a天气同步插件配置已重载。");
            return true;
        }
        return false;
    }
}