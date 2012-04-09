package me.exote.webauctionplus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.exote.webauctionplus.dao.Auction;
import me.exote.webauctionplus.dao.AuctionItem;
import me.exote.webauctionplus.dao.AuctionMail;
import me.exote.webauctionplus.dao.AuctionPlayer;
import me.exote.webauctionplus.dao.SaleAlert;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

public class MySQLDataQueries {
	// itemTableId
	// 0 = WA_Items
	// 1 = 
	// 2 = WA_Mail

	private List<Boolean> inuse = new ArrayList<Boolean> (4);
	private List<Connection> connections = new ArrayList<Connection> (4);

	private WebAuctionPlus plugin;
	private String dbHost;
	private String dbPort;
	private String dbUser;
	private String dbPass;
	private String dbName;
	public int ConnPoolSizeWarn = 6;
	public int ConnPoolSizeHard = 20;
	public boolean debugSQL = false;

	public MySQLDataQueries(WebAuctionPlus plugin, String dbHost, String dbPort,
			String dbUser, String dbPass, String dbName) {
		this.plugin = plugin;
		this.dbHost = dbHost;
		this.dbPort = dbPort;
		this.dbUser = dbUser;
		this.dbPass = dbPass;
		this.dbName = dbName;
	}

	public Connection getConnection() {
		synchronized (inuse) {
			for(int i = 0; i != inuse.size(); i++) {
				if(!inuse.get(i)) {
					inuse.set(i, true);
					try {
						if(connections.get(i).isValid(2) == false) {
							inuse.remove(i);
							connections.remove(i);
							break;
						}
					} catch (SQLException e) {
						e.printStackTrace();
					}
					return connections.get(i);
				}
			}
		}

		if(connections.size() >= ConnPoolSizeHard) {
			plugin.log.severe(plugin.logPrefix + "DB connection pool is full! Hard limit reached!  Size:" + Integer.toString(connections.size()));
			return null;
		} else if(connections.size() >= ConnPoolSizeWarn) {
			plugin.log.warning(plugin.logPrefix + "DB connection pool is full! Warning limit reached.  Size: " + Integer.toString(connections.size()));
		}
		try {
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			Connection conn = DriverManager.getConnection("jdbc:mysql://"+dbHost+":"+dbPort+"/"+dbName, dbUser, dbPass);
			connections.add(conn);
			inuse.add(true);
			return conn;
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		plugin.log.severe(plugin.logPrefix + "Exception getting mySQL Connection");
		return null;
	}

	public void releaseConnection(Connection conn) {
		boolean valid = false;
		try {
			valid = conn.isValid(1);
		} catch (SQLException e) {
			e.printStackTrace();
		}
		synchronized(inuse) {
			int i = connections.indexOf(conn);
			inuse.set(i, false);
			if(!valid) {
				inuse.remove(i);
				connections.remove(i);
			}
		}
	}

	public void closeResources(Connection conn, Statement st, ResultSet rs) {
		releaseConnection(conn);
		closeResources(st, rs);
	}
	public void closeResources(Statement st, ResultSet rs) {
		if (rs != null) {
			try {
				rs.close();
			} catch (SQLException e) {}
		}
		if (st != null) {
			try {
				st.close();
			} catch (SQLException e) {}
		}
	}

	public void forceCloseConnections() {
		for(int i = 0; i != inuse.size(); i++) {
			try {
				connections.get(i).close();
			} catch (SQLException e) {}
		}
	}

	private void executeRawSQL(String sql) {
		Connection conn = getConnection();
		Statement st = null;
		ResultSet rs = null;
		try {
			st = conn.createStatement();
			st.executeUpdate(sql);
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Exception executing raw SQL: " + sql);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	private boolean tableExists(String tableName) {
		boolean exists = false;
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			st = conn.prepareStatement("SHOW TABLES LIKE ?");
			st.setString(1, plugin.dbPrefix + tableName);
			rs = st.executeQuery();
			while (rs.next()) {
				exists = true;
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to check if table exists: " + tableName);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return exists;
	}

	private void setTableExists(String tableName, String Sql) {
		if (tableExists(tableName)) {return;}
		plugin.log.info(plugin.logPrefix + "Creating table " + tableName);
		executeRawSQL("CREATE TABLE `" + plugin.dbPrefix + tableName + "` ( "+Sql+" );");
	}

	protected boolean columnExists(String tableName, String columnName) {
		boolean exists = false;
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			st = conn.prepareStatement("SHOW COLUMNS FROM `" + plugin.dbPrefix + tableName + "` LIKE ?");
			st.setString(1, columnName);
			rs = st.executeQuery();
			while (rs.next()) {
				exists = true;
				break;
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to check if table column exists: " + plugin.dbPrefix + tableName + "::" + columnName);
		}
		return exists;
	}

	protected void setColumnExists(String tableName, String columnName, String Attr) {
		if (columnExists(tableName, columnName)) {return;}
		plugin.log.info("Adding column " + columnName + " to table " + plugin.dbPrefix + tableName);
		executeRawSQL("ALTER TABLE `" + plugin.dbPrefix + tableName + "` ADD `" + columnName + "` " + Attr);
	}

	public void initTables() {
		setTableExists("Players",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT  , PRIMARY KEY(`id`), " +
			"`name`     VARCHAR(16)       NULL DEFAULT NULL    , " +
			"`pass`     VARCHAR(32)       NULL DEFAULT NULL    , " +
			"`money`     DOUBLE(11,2) NOT NULL DEFAULT '0.00'  , " +
			"`itemsSold`    INT(11)   NOT NULL DEFAULT '0'     , " +
			"`itemsBought`  INT(11)   NOT NULL DEFAULT '0'     , " +
			"`earnt`     DOUBLE(11,2) NOT NULL DEFAULT '0.00'  , " +
			"`spent`     DOUBLE(11,2) NOT NULL DEFAULT '0.00'  , " +
			"`canBuy`   TINYINT(1)    NOT NULL DEFAULT '0'     , " +
			"`canSell`  TINYINT(1)    NOT NULL DEFAULT '0'     , " +
			"`isAdmin`  TINYINT(1)    NOT NULL DEFAULT '0'"    );
		setTableExists("Items",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT  , PRIMARY KEY(`id`), " +
			"`name`         INT(11)   NOT NULL DEFAULT '0'     , " +
			"`damage`       INT(11)   NOT NULL DEFAULT '0'     , " +
			"`player`   VARCHAR(16)       NULL DEFAULT NULL    , " +
			"`quantity`     INT(11)   NOT NULL DEFAULT '0'"    );
		setTableExists("Enchantments",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT  , PRIMARY KEY(`id`), " +
			"`enchName` VARCHAR(32)       NULL DEFAULT NULL    , " +
			"`enchId`       INT(11)   NOT NULL DEFAULT '0'     , " +
			"`level`        INT(11)   NOT NULL DEFAULT '0'"    );
		setTableExists("EnchantLinks",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT  , PRIMARY KEY(`id`), " +
			"`enchId`       INT(11)   NOT NULL DEFAULT '0'     , " +
			"`itemTableId`  INT(11)   NOT NULL DEFAULT '0'     , " +
			"`itemId`       INT(11)   NOT NULL DEFAULT '0'"    );
		setTableExists("Auctions",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT  , PRIMARY KEY(`id`), " +
			"`name`         INT(11)   NOT NULL DEFAULT '0'     , " +
			"`damage`       INT(11)   NOT NULL DEFAULT '0'     , " +
			"`player`   VARCHAR(16)       NULL DEFAULT NULL    , " +
			"`quantity`     INT(11)   NOT NULL DEFAULT '0'     , " +
			"`price`     DOUBLE(11,2) NOT NULL DEFAULT '0.00'  , " +
			"`created` DATETIME       NOT NULL DEFAULT '0000-00-00 00:00:00', " +
			"`allowBids` TINYINT(1)   NOT NULL DEFAULT '0'     , " +
			"`currentBid` DOUBLE(11,2) NOT NULL DEFAULT '0.00' , " +
			"`currentWinner` VARCHAR(16)  NULL DEFAULT NULL" );
		setTableExists("SellPrice",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT  , PRIMARY KEY(`id`), " +
			"`name`         INT(11)   NOT NULL DEFAULT '0'     , " +
			"`damage`       INT(11)   NOT NULL DEFAULT '0'     , " +
			"`time`    DATETIME       NOT NULL DEFAULT '0000-00-00 00:00:00', " +
			"`quantity`     INT(11)   NOT NULL DEFAULT '0'     , " +
			"`price`     DOUBLE(11,2) NOT NULL DEFAULT '0.00'  , " +
			"`seller`   VARCHAR(16)       NULL DEFAULT NULL      , " +
			"`buyer`    VARCHAR(16)       NULL DEFAULT NULL"     );
		setTableExists("MarketPrices",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT  , PRIMARY KEY(`id`), " +
			"`name`         INT(11)   NOT NULL DEFAULT '0'     , " +
			"`damage`       INT(11)   NOT NULL DEFAULT '0'     , " +
			"`time`    DATETIME       NOT NULL DEFAULT '0000-00-00 00:00:00', " +
			"`marketprice` DOUBLE(11,2) NOT NULL DEFAULT '0.00', " +
			"`ref`          INT(11)   NOT NULL DEFAULT '0'"    );
		setTableExists("Mail",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT  , PRIMARY KEY(`id`), " +
			"`name`         INT(11)   NOT NULL DEFAULT '0'     , " +
			"`damage`       INT(11)   NOT NULL DEFAULT '0'     , " +
			"`player`   VARCHAR(16)       NULL DEFAULT NULL    , " +
			"`quantity`     INT(11)   NOT NULL DEFAULT '0'"    );
		setTableExists("RecentSigns",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT, PRIMARY KEY(`id`), " +
			"`world`    VARCHAR(32)       NULL DEFAULT NULL    , " +
			"`offset`       INT(11)   NOT NULL DEFAULT '0'     , " +
			"`x`            INT(11)   NOT NULL DEFAULT '0'     , " +
			"`y`            INT(11)   NOT NULL DEFAULT '0'     , " +
			"`z`            INT(11)   NOT NULL DEFAULT '0'"    );
		setTableExists("ShoutSigns",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT, PRIMARY KEY(`id`), " +
			"`world`    VARCHAR(32)       NULL DEFAULT NULL    , " +
			"`radius`       INT(11)   NOT NULL DEFAULT '0'     , " +
			"`x`            INT(11)   NOT NULL DEFAULT '0'     , " +
			"`y`            INT(11)   NOT NULL DEFAULT '0'     , " +
			"`z`            INT(11)   NOT NULL DEFAULT '0'"    );
		setTableExists("SaleAlerts",
			"`id`           INT(11)   NOT NULL AUTO_INCREMENT, PRIMARY KEY(`id`), " +
			"`seller`   VARCHAR(16)       NULL DEFAULT NULL    , " +
			"`quantity`     INT(11)   NOT NULL DEFAULT '0'     , " +
			"`price`     DOUBLE(11,2) NOT NULL DEFAULT '0.00'  , " +
			"`buyer`    VARCHAR(16)       NULL DEFAULT NULL    , " +
			"`item`     VARCHAR(16)       NULL DEFAULT NULL    , " +
			"`alerted`  TINYINT(1)    NOT NULL DEFAULT '0'"    );
	}

	public int getMaxAuctionID() {
		int maxAuctionID = -1;
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getMaxAuctionID");
			st = conn.prepareStatement("SELECT MAX(`id`) FROM `" + plugin.dbPrefix + "Auctions`");
			rs = st.executeQuery();
			if (rs.next())
				maxAuctionID = rs.getInt(1);
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to query for max Auction ID");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return maxAuctionID;
	}

	public Map<Location, Integer> getShoutSignLocations() {
		Map<Location, Integer> signLocations = new HashMap<Location, Integer>();
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getShoutSignLocations");
			st = conn.prepareStatement("SELECT `world`,`radius`,`x`,`y`,`z` FROM `" + plugin.dbPrefix + "ShoutSigns`");
			Location location;
			rs = st.executeQuery();
			while (rs.next()) {
				World world = plugin.getServer().getWorld(rs.getString("world"));
				location = new Location(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
				signLocations.put(location,    rs.getInt("radius"));
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get shout sign locations");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return signLocations;
	}

	public Map<Location, Integer> getRecentSignLocations() {
		Map<Location, Integer> signLocations = new HashMap<Location, Integer>();
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getRecentSignLocations");
			st = conn.prepareStatement("SELECT `world`,`offset`,`x`,`y`,`z` FROM `" + plugin.dbPrefix + "RecentSigns`");
			Location location;
			rs = st.executeQuery();
			while (rs.next()) {
				World world = plugin.getServer().getWorld(rs.getString("world"));
				location = new Location(world, rs.getInt("x"), rs.getInt("y"), rs.getInt("z"));
				signLocations.put(location,    rs.getInt("offset"));
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get shout sign locations");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return signLocations;
	}

	public List<SaleAlert> getNewSaleAlertsForSeller(String player) {
		List<SaleAlert> saleAlerts = new ArrayList<SaleAlert>();
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getNewSaleAlertsForSeller " + player);
			st = conn.prepareStatement("SELECT `id`,`seller`,`quantity`,`price`,`buyer`,`item` FROM `" +
				plugin.dbPrefix + "SaleAlerts` WHERE `seller` = ? AND `alerted` = 0");
			st.setString(1, player);
			SaleAlert saleAlert;
			rs = st.executeQuery();
			while (rs.next()) {
				saleAlert = new SaleAlert();
				saleAlert.setId(rs.getInt("id"));
				saleAlert.setBuyer(rs.getString("buyer"));
				saleAlert.setItem(rs.getString("item"));
				saleAlert.setQuantity(rs.getInt("quantity"));
				saleAlert.setPriceEach(rs.getDouble("price"));
				saleAlerts.add(saleAlert);
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get sale alerts for player " + player);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return saleAlerts;
	}

	public void markSaleAlertSeen(int id) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: markSaleAlertSeen " + Integer.toString(id));
			st = conn.prepareStatement("UPDATE `" + plugin.dbPrefix + "SaleAlerts` SET `alerted` = 1 WHERE `id` = ?");
			st.setInt(1, id);
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to mark sale alert seen " + id);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public Auction getAuction(int id) {
		Auction auction = null;
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getAuction " + Integer.toString(id));
			st = conn.prepareStatement("SELECT `name`,`damage`,`player`,`quantity`,`price`," +
				"UNIX_TIMESTANP(`created`),`allowBids`,`currentBid`,`currentWinner` FROM `WA_Auctions` WHERE `id` = ?");
			st.setInt(1, id);
			rs = st.executeQuery();
			while (rs.next()) {
				auction = new Auction();
				auction.setId(id);
				auction.setItemStack(new ItemStack(rs.getInt("name"), rs.getInt("quantity"), rs.getShort("damage")));
				auction.setPlayerName(rs.getString("player"));
				auction.setPrice(rs.getDouble("price"));
				auction.setCreated(rs.getInt("created"));
				auction.setAllowBids(rs.getBoolean("allowBids"));
				auction.setCurrentBid(rs.getDouble("currentBid"));
				auction.setCurrentWinner(rs.getString("currentWinner"));
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get auction " + id);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return auction;
	}

	public void removeShoutSign(Location location) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: removeShoutSign " + location.toString());
			st = conn.prepareStatement("DELETE FROM `" + plugin.dbPrefix + "ShoutSigns` WHERE " +
				"`world` = ? AND `x` = ? AND `y` = ? AND `z` = ?");
			st.setString(1, location.getWorld().getName());
			st.setInt(2, (int) location.getX());
			st.setInt(3, (int) location.getY());
			st.setInt(4, (int) location.getZ());
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to remove shout sign at location " + location);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public int getTotalAuctionCount() {
		int totalAuctionCount = 0;
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getTotalAuctionCount");
			st = conn.prepareStatement("SELECT COUNT(*) FROM `" + plugin.dbPrefix + "Auctions`");
			rs = st.executeQuery();
			if (rs.next())
				totalAuctionCount = rs.getInt(1);
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get total auction count");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return totalAuctionCount;
	}

	public Auction getAuctionForOffset(int offset) {
		Auction auction = null;
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getAuctionForOffset " + Integer.toString(offset));
			st = conn.prepareStatement("SELECT `name`,`damage`,`player`,`quantity`,`price`,UNIX_TIMESTAMP(`created`) " +
				"FROM `" + plugin.dbPrefix + "Auctions` ORDER BY `id` DESC LIMIT ?, 1");
			st.setInt(1, offset);
			rs = st.executeQuery();
			while (rs.next()) {
				auction = new Auction();
				auction.setId(offset);
				auction.setItemStack(new ItemStack(rs.getInt("name"), rs.getInt("quantity"), rs.getShort("damage")));
				auction.setPlayerName(rs.getString("player"));
				auction.setPrice(rs.getDouble("price"));
				auction.setCreated(rs.getInt("created"));
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get auction " + offset);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return auction;
	}

	public void removeRecentSign(Location location) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: removeRecentSign " + location.toString());
			st = conn.prepareStatement("DELETE FROM `" + plugin.dbPrefix + "RecentSigns` WHERE " +
				"`world` = ? AND `x` = ? AND `y` = ? AND `z` = ?");
			st.setString(1, location.getWorld().getName());
			st.setInt(2, (int) location.getX());
			st.setInt(3, (int) location.getY());
			st.setInt(4, (int) location.getZ());
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to remove recent sign at location " + location.toString());
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public void updatePlayerPassword(String player, String newPass) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: updatePlayerPassword " + player);
			st = conn.prepareStatement("UPDATE `" + plugin.dbPrefix + "Players` SET `pass` = ? WHERE `name` = ?");
			st.setString(1, newPass);
			st.setString(2, player);
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to update password for player: " + player);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public void createShoutSign(World world, int radius, int x, int y, int z) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: createShoutSign " +
				Integer.toString(radius) + " " + Integer.toString(x) + "," +
				Integer.toString(y) + "," + Integer.toString(z) );
			st = conn.prepareStatement("INSERT INTO `" + plugin.dbPrefix + "ShoutSigns` " +
				"(`world`, `radius`, `x`, `y`, `z`) VALUES (?, ?, ?, ?, ?)");
			st.setString(1, world.getName());
			st.setInt(2, radius);
			st.setInt(3, x);
			st.setInt(4, y);
			st.setInt(5, z);
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to create shout sign");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public void createRecentSign(World world, int offset, int x, int y, int z) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: createRecentSign " +
				world.getName() + " " + Integer.toString(offset) + " " +
				Integer.toString(x) + "," + Integer.toString(y) + "," + Integer.toString(z) );
			st = conn.prepareStatement("INSERT INTO `" + plugin.dbPrefix + "RecentSigns` " +
				"(`world`, `offset`, `x`, `y`, `z`) VALUES (?, ?, ?, ?, ?)");
			st.setString(1, world.getName());
			st.setInt(2, offset);
			st.setInt(3, x);
			st.setInt(4, y);
			st.setInt(5, z);
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to create recent sign");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public int hasMail(String player) {
		int mailCount = 0;
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: hasMail " + player);
			st = conn.prepareStatement("SELECT COUNT(*) FROM `" + plugin.dbPrefix + "Mail` WHERE `player` = ?");
			st.setString(1, player);
			rs = st.executeQuery();
			if (rs.next())
				mailCount = rs.getInt(1);
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to check new mail for: " + player);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return mailCount;
	}

	public AuctionPlayer getPlayer(String player) {
		AuctionPlayer waPlayer = null;
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getPlayer " + player);
			st = conn.prepareStatement("SELECT `id`,`name`,`money`,`canBuy`,`canSell`,`isAdmin` " +
				"FROM `" + plugin.dbPrefix + "Players` WHERE `name` = ?");
			st.setString(1, player);
			rs = st.executeQuery();
			while (rs.next()) {
				waPlayer = new AuctionPlayer();
				waPlayer.setId(     rs.getInt    ("id"));
				waPlayer.setName(   rs.getString ("name"));
				waPlayer.setMoney(  rs.getDouble ("money"));
				waPlayer.setCanBuy( rs.getBoolean("canBuy"));
				waPlayer.setCanSell(rs.getBoolean("canSell"));
				waPlayer.setIsAdmin(rs.getBoolean("isAdmin"));
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get player " + player);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return waPlayer;
	}

	public void updatePlayerPermissions(String player, AuctionPlayer auctionPlayer, boolean canBuy, boolean canSell, boolean isAdmin) {
		// return if update not needed
		if (Boolean.valueOf( canBuy  ).equals( auctionPlayer.getCanBuy()  ) &&
			Boolean.valueOf( canSell ).equals( auctionPlayer.getCanSell() ) &&
			Boolean.valueOf( isAdmin ).equals( auctionPlayer.getIsAdmin() ) ) {
			return;
		}
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: updatePlayerPermissions " + player);
			st = conn.prepareStatement("UPDATE `" + plugin.dbPrefix + "Players` SET " +
				"`canBuy` = ?, `canSell` = ?, `isAdmin` = ? WHERE `name` = ?");
			st.setInt(1, (canBuy) ?1:0 );
			st.setInt(2, (canSell)?1:0 );
			st.setInt(3, (isAdmin)?1:0 );
			st.setString(4, player);
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to update player permissions in DB");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public void createPlayer(String player, String pass, boolean canBuy, boolean canSell, boolean isAdmin) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: createPlayer " + player);
			st = conn.prepareStatement("INSERT INTO `" + plugin.dbPrefix + "Players` " +
				"(`name`, `pass`, `canBuy`, `canSell`, `isAdmin`) VALUES (?, ?, ?, ?, ?)");
			st.setString(1, player);
			st.setString(2, pass);
			st.setInt(3, (canBuy ?1:0));
			st.setInt(4, (canSell?1:0));
			st.setInt(5, (isAdmin?1:0));
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to update player permissions in DB");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public void updatePlayerMoney(String player, double money) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: updatePlayerMoney " + player);
			st = conn.prepareStatement("UPDATE `" + plugin.dbPrefix + "Players` SET `money` = ? WHERE `name` = ?");
			st.setDouble(1, money);
			st.setString(2, player);
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to update player money in DB");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public List<AuctionItem> getItems(String player, int itemID, int damage, boolean reverseOrder) {
		List<AuctionItem> auctionItems = new ArrayList<AuctionItem>();
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getItems " + player + " " +
				Integer.toString(itemID) + ":" + Integer.toString(damage) );
			st = conn.prepareStatement("SELECT `id`,`name`,`damage`,`player`,`quantity` " +
				"FROM `" + plugin.dbPrefix + "Items` WHERE " +
				"`player` = ? AND `name` = ? AND `damage` = ? " +
				"ORDER BY `id` " + (reverseOrder?"DESC":"ASC") );
			st.setString(1, player);
			st.setInt(2, itemID);
			st.setInt(3, damage);
			AuctionItem auctionItem;
			rs = st.executeQuery();
			while (rs.next()) {
				auctionItem = new AuctionItem();
				auctionItem.setId(rs.getInt("id"));
				auctionItem.setName(rs.getInt("name"));
				auctionItem.setDamage(rs.getInt("damage"));
				auctionItem.setPlayerName(rs.getString("player"));
				auctionItem.setQuantity(rs.getInt("quantity"));
				auctionItems.add(auctionItem);
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get items");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return auctionItems;
	}

	public int getEnchantTableID(int enchantID, int level, String enchantName) {
		int tableID = -1;
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getEnchantTableID " + enchantName);
			st = conn.prepareStatement("SELECT `id` FROM `" + plugin.dbPrefix + "Enchantments` " +
				"WHERE `enchId` = ? AND `level` = ? AND `enchName` = ? LIMIT 1");
			st.setInt(1, enchantID);
			st.setInt(2, level);
			st.setString(3, enchantName);
			rs = st.executeQuery();
			if (rs.next())
				tableID = rs.getInt("id");
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get items");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return tableID;
	}

	public void createEnchantment(String enchantName, int enchantID, int level) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: createEnchantment " + enchantName);
			st = conn.prepareStatement("INSERT INTO `" + plugin.dbPrefix + "Enchantments` " +
				"(`enchName`, `enchId`, `level`) VALUES (?, ?, ?)");
			st.setString(1, enchantName);
			st.setInt(2, enchantID);
			st.setInt(3, level);
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to create enchantment");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public List<Integer> getEnchantIDsForLinks(int itemID, int itemTableID) {
		List<Integer> enchantIDs = new ArrayList<Integer>();
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getEnchantIDsForLinks " +
				Integer.toString(itemID) + " " + Integer.toString(itemTableID) );
			st = conn.prepareStatement("SELECT `enchId` FROM `" + plugin.dbPrefix + "EnchantLinks` " +
				"WHERE `itemTableId` = ? AND `itemId` = ? ORDER BY `enchId` DESC");
			st.setInt(1, itemTableID);
			st.setInt(2, itemID);
			rs = st.executeQuery();
			while (rs.next()) {
				enchantIDs.add(rs.getInt("enchId"));
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get items");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return enchantIDs;
	}

	public void updateItemQuantity(int quantity, int id) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: updateItemQuantity " +
				Integer.toString(id) + " " + Integer.toString(quantity) );
			st = conn.prepareStatement("UPDATE `" + plugin.dbPrefix + "Items` SET `quantity` = ? WHERE `id` = ?");
			st.setInt(1, quantity);
			st.setInt(2, id);
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to update item quantity in DB");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public int createItem(int itemID, int itemDamage, String player, int quantity) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		int keyId = 0;
		try {
			if (debugSQL) plugin.log.info("WA Query: createItem " +
				Integer.toString(itemID) + ":" + Integer.toString(itemDamage) );
			st = conn.prepareStatement("INSERT INTO `" + plugin.dbPrefix + "Items` " +
				"(`name`, `damage`, `player`, `quantity`) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
			st.setInt(1, itemID);
			st.setInt(2, itemDamage);
			st.setString(3, player);
			st.setInt(4, quantity);
			int affectedRows=st.executeUpdate();
			if (affectedRows==0) {
				throw new SQLException("Creating new item failed, no rows affected.");
			}
			// get insert id
			ResultSet gKeys = st.getGeneratedKeys();
			if (gKeys.next()) {
				keyId = gKeys.getInt(1);
			} else {
				throw new SQLException("Creating new item failed, no generated key.");
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to create item");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return keyId;
	}

	public void createEnchantLink(int enchantID, int itemTableID, int itemID) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: createEnchantLink " +
				Integer.toString(enchantID) + " " + Integer.toString(itemTableID) + " " + Integer.toString(itemID) );
			st = conn.prepareStatement("INSERT INTO `" + plugin.dbPrefix + "EnchantLinks` " +
				"(`enchId`, `itemTableId`, `itemId`) VALUES (?, ?, ?)");
			st.setInt(1, enchantID);
			st.setInt(2, itemTableID);
			st.setInt(3, itemID);
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to create item");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

	public List<AuctionMail> getMail(String player) {
		List<AuctionMail> auctionMails = new ArrayList<AuctionMail>();
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getMail " + player);
			st = conn.prepareStatement("SELECT `id`,`name`,`damage`,`player`,`quantity` " +
				"FROM `" + plugin.dbPrefix + "Mail` WHERE `player` = ?");
			st.setString(1, player);
			AuctionMail auctionMail;
			rs = st.executeQuery();
			while (rs.next()) {
				auctionMail = new AuctionMail();
				auctionMail.setId(rs.getInt("id"));
				ItemStack stack = new ItemStack(rs.getInt("name"), rs.getInt("quantity"), rs.getShort("damage"));
				auctionMail.setItemStack(stack);
				auctionMail.setPlayerName(rs.getString("player"));
				auctionMails.add(auctionMail);
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get mail for player " + player);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return auctionMails;
	}

	public Map<Integer, Integer> getEnchantIDLevel(int id) {
		Map<Integer, Integer> returnInfo = new HashMap<Integer, Integer>();
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: getEnchantIDLevel " + Integer.toString(id) );
			st = conn.prepareStatement("SELECT `enchId`,`level` FROM `" + plugin.dbPrefix + "Enchantments` WHERE `id` = ?");
			st.setInt(1, id);
			rs = st.executeQuery();
			while (rs.next()) {
				returnInfo.put(rs.getInt("enchId"), rs.getInt("level"));
			}
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to get items");
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
		return returnInfo;
	}

// should change id argument to a list
	public void deleteMail(int id) {
		Connection conn = getConnection();
		PreparedStatement st = null;
		ResultSet rs = null;
		try {
			if (debugSQL) plugin.log.info("WA Query: deleteMail " + Integer.toString(id) );
			st = conn.prepareStatement("DELETE FROM `" + plugin.dbPrefix + "Mail` WHERE `id` = ?");
			st.setInt(1, id);
			st.executeUpdate();
		} catch (SQLException e) {
			plugin.log.warning(plugin.logPrefix + "Unable to remove mail " + id);
			e.printStackTrace();
		} finally {
			closeResources(conn, st, rs);
		}
	}

}