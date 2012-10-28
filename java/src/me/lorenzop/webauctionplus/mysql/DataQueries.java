package me.lorenzop.webauctionplus.mysql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import me.lorenzop.webauctionplus.WebAuctionPlus;
import me.lorenzop.webauctionplus.dao.Auction;
import me.lorenzop.webauctionplus.dao.AuctionPlayer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class DataQueries {


	// encode/decode enchantments for database storage
	public static String encodeEnchantments(Player p, ItemStack stack) {
		if(stack == null) return "";
		Map<Enchantment, Integer> enchantments = stack.getEnchantments();
		if(enchantments==null || enchantments.isEmpty()) return "";
		// get enchantments
		HashMap<Integer, Integer> enchMap = new HashMap<Integer, Integer>();
		boolean removedUnsafe = false;
		for(Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
			// check safe enchantments
			int level = checkSafeEnchantments(stack, entry.getKey(), entry.getValue() );
			if(level == 0) {
				removedUnsafe = true;
				continue;
			}
			enchMap.put(entry.getKey().getId(), level);
		}
		if(removedUnsafe && p != null) p.sendMessage(WebAuctionPlus.logPrefix+WebAuctionPlus.Lang.getString("removed_enchantments"));
		// sort by enchantment id
		SortedSet<Integer> enchSorted = new TreeSet<Integer> (enchMap.keySet());
		// build string
		String enchStr = "";
		for(int enchId : enchSorted) {
			int level = enchMap.get(enchId);
			if(!enchStr.isEmpty()) enchStr += ",";
			enchStr += Integer.toString(enchId)+":"+Integer.toString(level);
		}
		return enchStr;
	}
	// decode enchantments from database
	public static boolean decodeEnchantments(Player p, ItemStack stack, String enchStr) {
		if(enchStr == null || enchStr.isEmpty()) return false;
		Map<Enchantment, Integer> ench = new HashMap<Enchantment, Integer>();
		String[] parts = enchStr.split(",");
		boolean removedUnsafe = false;
		for(String part : parts) {
			if(part==null || part.isEmpty()) continue;
			String[] split = part.split(":");
			if(split.length != 2) {
				WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix+"Invalid enchantment data found: "+part);
				continue;
			}
			int enchId = -1;
			int level  = -1;
			try {
				enchId = Integer.valueOf(split[0]);
				level  = Integer.valueOf(split[1]);
			} catch(Exception ignore) {}
			if(enchId<0 || level<1) {
				WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix+"Invalid enchantment data found: "+part);
				continue;
			}
			Enchantment enchantment = Enchantment.getById(enchId);
			if(enchantment == null) {
				WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix+"Invalid enchantment id found: "+part);
				continue;
			}
			// check safe enchantments
			level = checkSafeEnchantments(stack, enchantment, level);
			if(level == 0) {
				removedUnsafe = true;
				continue;
			}
			// add enchantment to map
			ench.put(enchantment, level);
		}
		if(removedUnsafe) p.sendMessage(WebAuctionPlus.logPrefix+WebAuctionPlus.Lang.getString("removed_enchantments"));
		// add enchantments to stack
		if(WebAuctionPlus.timEnabled())
			stack.addUnsafeEnchantments(ench);
		else
			stack.addEnchantments(ench);
		return removedUnsafe;
	}
	// check natural enchantment
	public static int checkSafeEnchantments(ItemStack stack, Enchantment enchantment, int level) {
		if(stack == null || enchantment == null) return 0;
		if(level < 1) return 0;
		// can enchant item
		if(!enchantment.canEnchantItem(stack)) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix+"Removed unsafe enchantment: "+stack.toString()+"  "+enchantment.toString());
			return 0;
		}
		if(WebAuctionPlus.timEnabled()) {
			if(level > 127) level = 127;
		} else {
			// level too low
			if(level < enchantment.getStartLevel()) {
				WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix+"Raised unsafe enchantment: "+
					Integer.toString(level)+"  "+stack.toString()+"  "+enchantment.toString()+"  to level: "+enchantment.getStartLevel() );
				level = enchantment.getStartLevel();
			}
			// level too high
			if(level > enchantment.getMaxLevel()) {
				WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix+"Lowered unsafe enchantment: "+
					Integer.toString(level)+"  "+stack.toString()+"  "+enchantment.toString()+"  to level: "+enchantment.getMaxLevel() );
				level = enchantment.getMaxLevel();
			}
		}
		return level;
	}


	// auctions
	public static Auction getAuction(int auctionId) {
		Auction auction = null;
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: getAuction "+Integer.toString(auctionId));
			st = poolConn.getConn().prepareStatement("SELECT `playerName`, `itemId`, `itemDamage`, `qty`, `enchantments`, `itemTitle`, "+
				"`price`, `allowBids`, `currentBid`, `currentWinner` FROM `"+poolConn.dbPrefix()+"Auctions` WHERE `id` = ? LIMIT 1");
//UNIX_TIMESTANP(`created`) AS `created`,
			st.setInt(1, auctionId);
			rs = st.executeQuery();
			if(rs.next()) {
				auction = new Auction();
				auction.setOffset(auctionId);
				ItemStack stack = new ItemStack(rs.getInt("itemId"), rs.getInt("qty"), rs.getShort("itemDamage"));
				encodeEnchantments(null, stack);
				auction.setItemStack(stack);
				auction.setItemTitle(rs.getString("itemTitle"));
				auction.setPlayerName(rs.getString("playerName"));
				auction.setPrice(rs.getDouble("price"));
//				auction.setCreated(rs.getInt("created"));
				auction.setAllowBids(rs.getBoolean("allowBids"));
				auction.setCurrentBid(rs.getDouble("currentBid"));
				auction.setCurrentWinner(rs.getString("currentWinner"));
			}
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to get auction " + Integer.toString(auctionId));
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
		return auction;
	}


	// shout sign
	public static void createShoutSign(World world, int radius, int x, int y, int z) {
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: createShoutSign " +
				Integer.toString(radius) + " " + Integer.toString(x) + "," +
				Integer.toString(y) + "," + Integer.toString(z) );
			st = poolConn.getConn().prepareStatement("INSERT INTO `"+poolConn.dbPrefix()+"ShoutSigns` " +
				"(`world`, `radius`, `x`, `y`, `z`) VALUES (?, ?, ?, ?, ?)");
			st.setString(1, world.getName());
			st.setInt   (2, radius);
			st.setInt   (3, x);
			st.setInt   (4, y);
			st.setInt   (5, z);
			st.executeUpdate();
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to create shout sign");
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
	}
	public static void removeShoutSign(Location location) {
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: removeShoutSign " + location.toString());
			st = poolConn.getConn().prepareStatement("DELETE FROM `"+poolConn.dbPrefix()+"ShoutSigns` WHERE " +
				"`world` = ? AND `x` = ? AND `y` = ? AND `z` = ?");
			st.setString(1, location.getWorld().getName());
			st.setInt   (2, (int) location.getX());
			st.setInt   (3, (int) location.getY());
			st.setInt   (4, (int) location.getZ());
			st.executeUpdate();
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to remove shout sign at location " + location);
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
	}
	public static Map<Location, Integer> getShoutSignLocations() {
		Map<Location, Integer> signLocations = new HashMap<Location, Integer>();
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: getShoutSignLocations");
			st = poolConn.getConn().prepareStatement("SELECT `world`,`radius`,`x`,`y`,`z` FROM `"+poolConn.dbPrefix()+"ShoutSigns`");
			Location location;
			rs = st.executeQuery();
			while(rs.next()) {
				World world = Bukkit.getServer().getWorld(rs.getString("world"));
				location = new Location(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
				signLocations.put(location,    rs.getInt("radius"));
			}
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to get shout sign locations");
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
		return signLocations;
	}


	// recent sign
	public static void createRecentSign(World world, int offset, int x, int y, int z) {
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: createRecentSign " +
				world.getName() + " " + Integer.toString(offset) + " " +
				Integer.toString(x) + "," + Integer.toString(y) + "," + Integer.toString(z) );
			st = poolConn.getConn().prepareStatement("INSERT INTO `"+poolConn.dbPrefix()+"RecentSigns` " +
				"(`world`, `offset`, `x`, `y`, `z`) VALUES (?, ?, ?, ?, ?)");
			st.setString(1, world.getName());
			st.setInt   (2, offset);
			st.setInt   (3, x);
			st.setInt   (4, y);
			st.setInt   (5, z);
			st.executeUpdate();
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to create recent sign");
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
	}
	public static void removeRecentSign(Location location) {
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: removeRecentSign " + location.toString());
			st = poolConn.getConn().prepareStatement("DELETE FROM `"+poolConn.dbPrefix()+"RecentSigns` WHERE "+
				"`world` = ? AND `x` = ? AND `y` = ? AND `z` = ?");
			st.setString(1, location.getWorld().getName());
			st.setInt   (2, (int) location.getX());
			st.setInt   (3, (int) location.getY());
			st.setInt   (4, (int) location.getZ());
			st.executeUpdate();
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to remove recent sign at location " + location.toString());
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
	}
	public static Map<Location, Integer> getRecentSignLocations() {
		Map<Location, Integer> signLocations = new HashMap<Location, Integer>();
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: getRecentSignLocations");
			st = poolConn.getConn().prepareStatement("SELECT `world`,`offset`,`x`,`y`,`z` FROM `"+poolConn.dbPrefix()+"RecentSigns`");
			Location location;
			rs = st.executeQuery();
			while(rs.next()) {
				World world = Bukkit.getServer().getWorld(rs.getString("world"));
				location = new Location(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
				signLocations.put(location,    rs.getInt("offset"));
			}
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to get shout sign locations");
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
		return signLocations;
	}


	public static AuctionPlayer getPlayer(String player) {
		AuctionPlayer waPlayer = null;
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: getPlayer " + player);
			st = poolConn.getConn().prepareStatement("SELECT `id`,`playerName`,`money`,`Permissions` " +
				"FROM `"+poolConn.dbPrefix()+"Players` WHERE `playerName` = ? LIMIT 1");
			st.setString(1, player);
			rs = st.executeQuery();
			if(rs.next()) {
				waPlayer = new AuctionPlayer();
				waPlayer.setPlayerId(  rs.getInt   ("id"));
				waPlayer.setPlayerName(rs.getString("playerName"));
				waPlayer.setMoney(     rs.getDouble("money"));
				waPlayer.setPerms(     rs.getString("Permissions"));
			}
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to get player " + player);
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
		return waPlayer;
	}


	public static void createPlayer(AuctionPlayer waPlayer, String pass) {
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: createPlayer " + waPlayer.getPlayerName() +
				" with perms: " + waPlayer.getPermsString());
			st = poolConn.getConn().prepareStatement("INSERT INTO `"+poolConn.dbPrefix()+"Players` " +
				"(`playerName`, `password`, `Permissions`) VALUES (?, ?, ?)");
			st.setString(1, waPlayer.getPlayerName());
			st.setString(2, pass);
			st.setString(3, waPlayer.getPermsString());
			st.executeUpdate();
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to update player permissions in DB");
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
	}


	public static void updatePlayerPassword(String player, String newPass) {
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: updatePlayerPassword " + player);
			st = poolConn.getConn().prepareStatement("UPDATE `"+poolConn.dbPrefix()+"Players` SET `password` = ? WHERE `playerName` = ? LIMIT 1");
			st.setString(1, newPass);
			st.setString(2, player);
			st.executeUpdate();
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to update password for player: " + player);
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
	}


	public static void updatePlayerPermissions(AuctionPlayer waPlayer, boolean canBuy, boolean canSell, boolean isAdmin) {
		// return if update not needed
		if(waPlayer.comparePerms(canBuy, canSell, isAdmin)) return;
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			// update player permissions for website
			waPlayer.setPerms(canBuy, canSell, isAdmin);
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: updatePlayerPermissions " + waPlayer.getPlayerName() +
				" with perms: " + waPlayer.getPermsString());
			st = poolConn.getConn().prepareStatement("UPDATE `"+poolConn.dbPrefix()+"Players` SET " +
				"`Permissions` = ? WHERE `playerName` = ? LIMIT 1");
			st.setString(1, waPlayer.getPermsString());
			st.setString(2, waPlayer.getPlayerName());
			st.executeUpdate();
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to update player permissions in DB");
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
	}


	public static void updatePlayerMoney(String player, double money) {
		MySQLPoolConn poolConn = WebAuctionPlus.dbPool.getLock();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if(WebAuctionPlus.isDebug()) WebAuctionPlus.log.info("WA Query: updatePlayerMoney " + player);
			st = poolConn.getConn().prepareStatement("UPDATE `"+poolConn.dbPrefix()+"Players` SET `money` = ? WHERE `playerName` = ?");
			st.setDouble(1, money);
			st.setString(2, player);
			st.executeUpdate();
		} catch(SQLException e) {
			WebAuctionPlus.log.warning(WebAuctionPlus.logPrefix + "Unable to update player money in DB");
			e.printStackTrace();
		} finally {
			poolConn.releaseLock(st, rs);
			poolConn = null;
		}
	}


}