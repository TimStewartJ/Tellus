package com.seibel.distanthorizons.fabric.mixins;

import com.seibel.distanthorizons.common.commonMixins.AbstractDhMixinPlugin;
import com.seibel.distanthorizons.fabric.wrappers.modAccessor.ModChecker;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * @author coolGi
 * @author cortex
 */
public class FabricMixinPlugin extends AbstractDhMixinPlugin implements IMixinConfigPlugin
{
	
	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName)
	{
		if (mixinClassName.contains("NativeChunkReadiness"))
		{
			#if MC_VER != MC_26_2_0
			return false;
			#endif
		}
		if (mixinClassName.contains(".mods."))
		{
			String cleanedMixinName = mixinClassName
				// What these 2 regex's do is get the mod name that we are checking out of the mixinClassName
				// Eg. "com.seibel.distanthorizons.mixins.mods.sodium.MixinSodiumChunkRenderer" turns into "sodium"
				.replaceAll("^.*mods.", "") // Replaces everything before the mods
				.replaceAll("\\..*$", ""); // Replaces everything after the mod name
			
			// If the mixin wants to go into a mod then we check if that mod is loaded or not
			return FabricLoader.getInstance().isModLoaded(cleanedMixinName);
		}
		
		if (!this.shouldApplyDhMixin(targetClassName, mixinClassName))
		{
			return false;
		}
		
		return true;
	}
	
	
	@Override
	public void onLoad(String mixinPackage)
	{
		
	}
	
	@Override
	public String getRefMapperConfig()
	{
		return null;
	}
	
	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets)
	{
		
	}
	
	@Override
	public List<String> getMixins()
	{
		return null;
	}
	
	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo)
	{
		
	}
	
	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo)
	{
		
	}
	
}