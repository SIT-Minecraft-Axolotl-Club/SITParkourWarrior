package club.sitmc.sitParkourWarrior.map;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;

public class SchematicService {
    public boolean saveRegionToSchem(Region region, File targetFile) {
        if (region == null || targetFile == null) {
            return false;
        }
        org.bukkit.World bukkitWorld = Bukkit.getWorld(region.getWorldName());
        if (bukkitWorld == null) {
            return false;
        }
        World weWorld = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld);
        BlockVector3 min = BlockVector3.at(region.getMinX(), region.getMinY(), region.getMinZ());
        BlockVector3 max = BlockVector3.at(region.getMaxX(), region.getMaxY(), region.getMaxZ());
        CuboidRegion weRegion = new CuboidRegion(weWorld, min, max);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(weRegion);

        try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder().world(weWorld).build()) {
            editSession.setTrackingHistory(false);
            ForwardExtentCopy copy = new ForwardExtentCopy(editSession, weRegion, clipboard, weRegion.getMinimumPoint());
            Operations.complete(copy);
        } catch (Throwable t) {
            return false;
        }

        ClipboardFormat format = ClipboardFormats.findByAlias("schem");
        if (format == null) {
            return false;
        }

        targetFile.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(targetFile); ClipboardWriter writer = format.getWriter(fos)) {
            writer.write(clipboard);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean pasteSchematic(File schemFile, Location origin) {
        if (schemFile == null || origin == null || origin.getWorld() == null) {
            return false;
        }
        if (!schemFile.exists()) {
            return false;
        }
        ClipboardFormat format = ClipboardFormats.findByFile(schemFile);
        if (format == null) {
            return false;
        }
        org.bukkit.World bukkitWorld = origin.getWorld();
        World weWorld = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld);
        try (FileInputStream fis = new FileInputStream(schemFile); ClipboardReader reader = format.getReader(fis)) {
            Clipboard clipboard = reader.read();
            try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                    .world(weWorld)
                    .build()) {
                editSession.setTrackingHistory(false);
                tryDisableSideEffects(editSession);

                ClipboardHolder holder = new ClipboardHolder(clipboard);
                Operation operation = holder.createPaste(editSession)
                        .to(BlockVector3.at(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ()))
                        .ignoreAirBlocks(false)
                        .build();
                Operations.complete(operation);
                editSession.flushSession();
                return true;
            }
        } catch (IOException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean clearRegion(Region region) {
        if (region == null) {
            return false;
        }
        org.bukkit.World bukkitWorld = Bukkit.getWorld(region.getWorldName());
        if (bukkitWorld == null) {
            return false;
        }
        World weWorld = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld);
        BlockVector3 min = BlockVector3.at(region.getMinX(), region.getMinY(), region.getMinZ());
        BlockVector3 max = BlockVector3.at(region.getMaxX(), region.getMaxY(), region.getMaxZ());
        CuboidRegion weRegion = new CuboidRegion(weWorld, min, max);
        try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder().world(weWorld).build()) {
            editSession.setTrackingHistory(false);
            tryDisableSideEffects(editSession);
            editSession.setBlocks(weRegion, BlockTypes.AIR.getDefaultState());
            editSession.flushSession();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void tryDisableSideEffects(EditSession editSession) {
        try {
            Class<?> sideEffectSetClass = Class.forName("com.sk89q.worldedit.world.block.SideEffectSet");
            Method noneMethod = sideEffectSetClass.getMethod("none");
            Object noneSet = noneMethod.invoke(null);
            Method setSideEffect = EditSession.class.getMethod("setSideEffectApplier", sideEffectSetClass);
            setSideEffect.invoke(editSession, noneSet);
        } catch (Throwable ignored) {
            // Fallback to default behavior when side effect API is not available.
        }
    }
}
