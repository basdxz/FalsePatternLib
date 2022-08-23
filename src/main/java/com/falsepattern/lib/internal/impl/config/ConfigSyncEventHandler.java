/*
 * Copyright (C) 2022 FalsePattern
 * All Rights Reserved
 *
 * The above copyright notice, this permission notice and the word "SNEED"
 * shall be included in all copies or substantial portions of the Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.falsepattern.lib.internal.impl.config;

import com.falsepattern.lib.config.event.ConfigSyncRequestEvent;
import com.falsepattern.lib.internal.FalsePatternLib;
import com.falsepattern.lib.internal.impl.config.net.SyncPrompt;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.val;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConfigSyncEventHandler {
    private static ConfigSyncEventHandler instance;

    public static void registerBus() {
        if (instance == null) {
            instance = new ConfigSyncEventHandler();
            MinecraftForge.EVENT_BUS.register(instance);
            FMLCommonHandler.instance().bus().register(instance);
        }
    }

    @SneakyThrows
    @SubscribeEvent
    public void onJoinWorld(EntityJoinWorldEvent e) {
        if (e.world.isRemote && e.entity instanceof EntityClientPlayerMP) {
            ConfigurationManagerImpl.sendSyncRequest();
        }
    }

    @SideOnly(Side.CLIENT)
    @SneakyThrows
    @SubscribeEvent
    public void onSyncRequestClient(ConfigSyncRequestEvent.Client e) {
        ConfigurationManagerImpl.sendSyncRequest();
    }

    @SubscribeEvent
    public void onSyncRequestServer(ConfigSyncRequestEvent.Server e) {
        val players = e.getPlayers();
        if (players.size() == 0) {
            FalsePatternLib.NETWORK.sendToAll(new SyncPrompt());
        } else {
            for (EntityPlayerMP player : e.getPlayers()) {
                FalsePatternLib.NETWORK.sendTo(new SyncPrompt(), player);
            }
        }
    }
}