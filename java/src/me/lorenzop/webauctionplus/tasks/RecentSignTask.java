package me.lorenzop.webauctionplus.tasks;

import java.util.ArrayList;
import java.util.List;

import me.lorenzop.webauctionplus.WebAuctionPlus;
import me.lorenzop.webauctionplus.dao.Auction;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.inventory.ItemStack;

import com.bergerkiller.bukkit.sl.API.Variable;
import com.bergerkiller.bukkit.sl.API.Variables;

public class RecentSignTask implements Runnable {

	private final WebAuctionPlus plugin;

	public RecentSignTask(WebAuctionPlus plugin) {
		this.plugin = plugin;
	}

	public void run() {
		if(Bukkit.getServer().getOnlinePlayers().length == 0) return;

		List<Location> toRemove = new ArrayList<Location>();
		List<Variable> WANames = new ArrayList<Variable>();
		List<Variable> WAPrices = new ArrayList<Variable>();
		List<Variable> WAQuants = new ArrayList<Variable>();
		List<Variable> WASellers = new ArrayList<Variable>();

		int totalAuctionCount = WebAuctionPlus.Stats.getTotalAuctions();
		if(WebAuctionPlus.useSignLink()) {
			for(int i = 0; i < plugin.numberOfRecentLink; i++) {
				Variable tempName = Variables.get("WAName" + i);
				Variable tempQuant = Variables.get("WAQuant" + i);
				Variable tempPrice = Variables.get("WAPrice" + i);
				Variable tempSeller = Variables.get("WASeller" + i);
				tempName.setDefault("N/A");
				tempQuant.setDefault("N/A");
				tempPrice.setDefault("N/A");
				tempSeller.setDefault("N/A");
				if(i < totalAuctionCount-1) {
					Auction offsetAuction = WebAuctionPlus.dataQueries.getAuctionForOffset(i);
					if(offsetAuction == null) continue;
					ItemStack stack = offsetAuction.getItemStack();
					if(stack == null) continue;
					tempName.set(stack.getType().toString());
					tempQuant.set(Integer.toString(stack.getAmount()));
					tempPrice.set(plugin.economy.format(offsetAuction.getPrice()));
					tempSeller.set(offsetAuction.getPlayerName());
					WANames.add(tempName);
					WAQuants.add(tempQuant);
					WAPrices.add(tempPrice);
					WASellers.add(tempSeller);
				}
			}
		}
		if(WebAuctionPlus.useOriginalRecent()) {
			for(Location key : plugin.recentSigns.keySet()) {
				int offset = (Integer)plugin.recentSigns.get(key);
				if(offset <= totalAuctionCount) {
					Auction offsetAuction = WebAuctionPlus.dataQueries.getAuctionForOffset(offset - 1);
					if(offsetAuction == null) continue;
					ItemStack stack = offsetAuction.getItemStack();
					if(stack == null) continue;
					int qty = stack.getAmount();
					String formattedPrice = plugin.economy.format(offsetAuction.getPrice());
					if(key.getBlock().getType() == Material.SIGN_POST || key.getBlock().getType() == Material.WALL_SIGN) {
						Sign thisSign = (Sign)key.getBlock().getState();
						thisSign.setLine(1, stack.getType().toString());
						thisSign.setLine(2, "qty: " + Integer.toString(qty));
						thisSign.setLine(3, formattedPrice);
						thisSign.update();
					} else {
						toRemove.add(key);
					}
				} else if(key.getBlock().getType() == Material.SIGN_POST ||
						   key.getBlock().getType() == Material.WALL_SIGN) {
					Sign thisSign = (Sign) key.getBlock().getState();
					thisSign.setLine(1, "Recent");
					thisSign.setLine(2, Integer.toString(offset));
					thisSign.setLine(3, "Not Available");
					thisSign.update();
				} else {
					toRemove.add(key);
				}
			}
		}

		// Remove any signs flagged for removal
		for(Location signLoc : toRemove) {
			plugin.recentSigns.remove(signLoc);
			WebAuctionPlus.dataQueries.removeRecentSign(signLoc);
			WebAuctionPlus.log.info(WebAuctionPlus.logPrefix + "Removed invalid sign at location: " + signLoc);
		}
	}

}