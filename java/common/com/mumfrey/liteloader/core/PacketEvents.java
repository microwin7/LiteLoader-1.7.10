package com.mumfrey.liteloader.core;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.network.login.server.S02PacketLoginSuccess;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S3FPacketCustomPayload;

import com.mumfrey.liteloader.JoinGameListener;
import com.mumfrey.liteloader.PacketHandler;
import com.mumfrey.liteloader.ServerChatFilter;
import com.mumfrey.liteloader.api.InterfaceProvider;
import com.mumfrey.liteloader.api.Listener;
import com.mumfrey.liteloader.common.transformers.PacketEventInfo;
import com.mumfrey.liteloader.core.event.HandlerList;
import com.mumfrey.liteloader.core.event.HandlerList.ReturnLogicOp;
import com.mumfrey.liteloader.core.runtime.Packets;
import com.mumfrey.liteloader.interfaces.FastIterable;
import com.mumfrey.liteloader.interfaces.FastIterableDeque;
import com.mumfrey.liteloader.util.log.LiteLoaderLogger;

/**
 * Packet event handling
 *
 * @author Adam Mummery-Smith
 */
public abstract class PacketEvents implements InterfaceProvider
{
	protected static PacketEvents instance;
	
	class PacketHandlerList extends HandlerList<PacketHandler>
	{
		private static final long serialVersionUID = 1L;
		PacketHandlerList() { super(PacketHandler.class, ReturnLogicOp.AND_BREAK_ON_FALSE); }
	}
	
	/**
	 * Reference to the loader instance
	 */
	protected final LiteLoader loader;

	private PacketHandlerList packetHandlers[] = new PacketHandlerList[Packets.count()];
	
	private FastIterable<ServerChatFilter> serverChatFilters = new HandlerList<ServerChatFilter>(ServerChatFilter.class, ReturnLogicOp.AND_BREAK_ON_FALSE);
	private FastIterableDeque<JoinGameListener> joinGameListeners = new HandlerList<JoinGameListener>(JoinGameListener.class);
	
	private final int loginSuccessPacketId  = Packets.S02PacketLoginSuccess.getIndex();
	private final int serverChatPacketId    = Packets.S02PacketChat.getIndex();
	private final int clientChatPacketId    = Packets.C01PacketChatMessage.getIndex();
	private final int joinGamePacketId      = Packets.S01PacketJoinGame.getIndex();
	private final int serverPayloadPacketId = Packets.S3FPacketCustomPayload.getIndex();
	private final int clientPayloadPacketId = Packets.C17PacketCustomPayload.getIndex();
	
	public PacketEvents()
	{
		PacketEvents.instance = this;
		this.loader = LiteLoader.getInstance();
	}
	
	@Override
	public Class<? extends Listener> getListenerBaseType()
	{
		return Listener.class;
	}

	@Override
	public void registerInterfaces(InterfaceRegistrationDelegate delegate)
	{
		delegate.registerInterface(PacketHandler.class);
		delegate.registerInterface(JoinGameListener.class);
		delegate.registerInterface(ServerChatFilter.class);
	}

	@Override
	public void initProvider()
	{
	}
	
	/**
	 * @param joinGameListener
	 */
	public void registerJoinGameListener(JoinGameListener joinGameListener)
	{
		this.joinGameListeners.add(joinGameListener);
	}

	/**
	 * @param serverChatFilter
	 */
	public void registerServerChatFilter(ServerChatFilter serverChatFilter)
	{
		this.serverChatFilters.add(serverChatFilter);
	}

	public void registerPacketHandler(PacketHandler handler)
	{
		List<Class<? extends Packet>> handledPackets = handler.getHandledPackets();
		if (handledPackets != null)
		{
			for (Class<? extends Packet> packetClass : handledPackets)
			{
				String packetClassName = packetClass.getName();
				int packetId = Packets.indexOf(packetClassName);
				if (packetId == -1 || packetId >= this.packetHandlers.length)
				{
					LiteLoaderLogger.warning("PacketHandler %s attempted to register a handler for unupported packet class %s", handler.getName(), packetClassName);
					continue;
				}
				
				if (this.packetHandlers[packetId] == null)
				{
					this.packetHandlers[packetId] = new PacketHandlerList();
				}
				
				this.packetHandlers[packetId].add(handler);
			}
		}
	}
	
	public static void handlePacket(PacketEventInfo<Packet> e, INetHandler netHandler)
	{
		PacketEvents.instance.handlePacket(e, netHandler, e.getPacketId());
	}
		
	private void handlePacket(PacketEventInfo<Packet> e, INetHandler netHandler, int packetId)
	{
		if (this.handlePacketEvent(e, netHandler, packetId) || this.packetHandlers[packetId] == null || e.isCancelled())
		{
			return;
		}
		
		if (this.packetHandlers[packetId].all().handlePacket(netHandler, e.getSource()))
		{
			return;
		}
		
		e.cancel();
	}

	/**
	 * @param e
	 * @param netHandler
	 * @param packetId
	 * @param packet
	 * 
	 * @return true if the packet was handled by a local handler and shouldn't be forwarded to later handlers
	 */
	protected boolean handlePacketEvent(PacketEventInfo<Packet> e, INetHandler netHandler, int packetId)
	{
		Packet packet = e.getSource();

		if (packetId == this.loginSuccessPacketId)
		{
			this.handlePacket(e, netHandler, (S02PacketLoginSuccess)packet);
			return true;
		}
		
		if (packetId == this.serverChatPacketId)
		{
			this.handlePacket(e, netHandler, (S02PacketChat)packet);
			return true;
		}
		
		if (packetId == this.clientChatPacketId)
		{
			this.handlePacket(e, netHandler, (C01PacketChatMessage)packet);
			return true;
		}
		
		if (packetId == this.joinGamePacketId)
		{
			this.handlePacket(e, netHandler, (S01PacketJoinGame)packet);
			return true;
		}
		
		if (packetId == this.serverPayloadPacketId)
		{
			this.handlePacket(e, netHandler, (S3FPacketCustomPayload)packet);
			return true;
		}
		
		if (packetId == this.clientPayloadPacketId)
		{
			this.handlePacket(e, netHandler, (C17PacketCustomPayload)packet);
			return true;
		}
		
		return false;
	}
	
	/**
	 * @param e
	 * @param netHandler
	 * @param packet
	 */
	protected abstract void handlePacket(PacketEventInfo<Packet> e, INetHandler netHandler, S02PacketLoginSuccess packet);
	
	/**
	 * S02PacketChat::processPacket()
	 * 
	 * @param netHandler
	 * @param packet
	 */
	protected abstract void handlePacket(PacketEventInfo<Packet> e, INetHandler netHandler, S02PacketChat packet);
	
	/**
	 * S02PacketChat::processPacket()
	 * 
	 * @param netHandler
	 * @param packet
	 */
	protected void handlePacket(PacketEventInfo<Packet> e, INetHandler netHandler, C01PacketChatMessage packet)
	{
		EntityPlayerMP player = netHandler instanceof NetHandlerPlayServer ? ((NetHandlerPlayServer)netHandler).playerEntity : null;
		
		if (!this.serverChatFilters.all().onChat(player, packet, packet.func_149439_c()))
		{
			e.cancel();
		}
	}
	
	/**
	 * S01PacketJoinGame::processPacket()
	 * 
	 * @param netHandler
	 * @param packet
	 */
	protected void handlePacket(PacketEventInfo<Packet> e, INetHandler netHandler, S01PacketJoinGame packet)
	{
		this.loader.onJoinGame(netHandler, packet);
		this.joinGameListeners.all().onJoinGame(netHandler, packet);
	}
	
	/**
	 * S3FPacketCustomPayload::processPacket()
	 * 
	 * @param netHandler
	 * @param packet
	 */
	protected void handlePacket(PacketEventInfo<Packet> e, INetHandler netHandler, S3FPacketCustomPayload packet)
	{
		LiteLoader.getClientPluginChannels().onPluginChannelMessage(packet);
	}
	
	/**
	 * C17PacketCustomPayload::processPacket()
	 * 
	 * @param netHandler
	 * @param packet
	 */
	protected void handlePacket(PacketEventInfo<Packet> e, INetHandler netHandler, C17PacketCustomPayload packet)
	{
		LiteLoader.getServerPluginChannels().onPluginChannelMessage(netHandler, packet);
	}
}
