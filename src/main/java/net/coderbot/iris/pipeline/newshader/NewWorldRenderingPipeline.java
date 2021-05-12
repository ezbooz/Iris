package net.coderbot.iris.pipeline.newshader;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.layer.GbufferProgram;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.postprocess.CompositeRenderer;
import net.coderbot.iris.rendertarget.NoiseTexture;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.rendertarget.SingleColorTexture;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.shadows.EmptyShadowMapRenderer;
import net.coderbot.iris.vertices.IrisVertexFormats;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Shader;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;

import java.io.IOException;
import java.util.Optional;

public class NewWorldRenderingPipeline implements WorldRenderingPipeline, CoreWorldRenderingPipeline {
	private final RenderTargets renderTargets;

	private final Shader skyBasic;
	private final Shader skyBasicColor;
	private final Shader skyTextured;
	private final Shader terrainSolid;
	private final Shader terrainCutout;
	private final Shader terrainCutoutMipped;
	private final Shader terrainTranslucent;
	private WorldRenderingPhase phase = WorldRenderingPhase.NOT_RENDERING_WORLD;

	private final GlFramebuffer clearAltBuffers;
	private final GlFramebuffer clearMainBuffers;
	private final GlFramebuffer baseline;

	private final EmptyShadowMapRenderer shadowMapRenderer;
	private final CompositeRenderer compositeRenderer;
	private final SingleColorTexture normals;
	private final SingleColorTexture specular;
	private final NoiseTexture noise;

	private final int waterId;
	private final float sunPathRotation;

	public NewWorldRenderingPipeline(ProgramSet programSet) throws IOException {
		this.renderTargets = new RenderTargets(MinecraftClient.getInstance().getFramebuffer(), programSet.getPackDirectives());
		this.waterId = programSet.getPack().getIdMap().getBlockProperties().getOrDefault(new Identifier("minecraft", "water"), -1);
		this.sunPathRotation = programSet.getPackDirectives().getSunPathRotation();

		Optional<ProgramSource> skyTexturedSource = first(programSet.getGbuffersSkyTextured(), programSet.getGbuffersTextured(), programSet.getGbuffersBasic());
		Optional<ProgramSource> skyBasicSource = first(programSet.getGbuffersSkyBasic(), programSet.getGbuffersBasic());

		Optional<ProgramSource> terrainSource = first(programSet.getGbuffersTerrain(), programSet.getGbuffersTexturedLit(), programSet.getGbuffersTextured(), programSet.getGbuffersBasic());
		Optional<ProgramSource> translucentSource = first(programSet.getGbuffersWater(), terrainSource);

		this.baseline = renderTargets.createFramebufferWritingToMain(new int[] {0});

		// TODO: Resolve hasColorAttrib based on the vertex format
		this.skyBasic = NewShaderTests.create("gbuffers_sky_basic", skyBasicSource.orElseThrow(RuntimeException::new), renderTargets, baseline, 0.0F, VertexFormats.POSITION, false);
		this.skyBasicColor = NewShaderTests.create("gbuffers_sky_basic_color", skyBasicSource.orElseThrow(RuntimeException::new), renderTargets, baseline, 0.0F, VertexFormats.POSITION_COLOR, true);
		this.skyTextured = NewShaderTests.create("gbuffers_sky_textured", skyTexturedSource.orElseThrow(RuntimeException::new), renderTargets, baseline, 0.0F, VertexFormats.POSITION_TEXTURE, false);
		this.terrainSolid = NewShaderTests.create("gbuffers_terrain_solid", terrainSource.orElseThrow(RuntimeException::new), renderTargets, baseline, 0.0F, IrisVertexFormats.TERRAIN, true);
		this.terrainCutout = NewShaderTests.create("gbuffers_terrain_cutout", terrainSource.orElseThrow(RuntimeException::new), renderTargets, baseline, 0.1F, IrisVertexFormats.TERRAIN, true);
		this.terrainCutoutMipped = NewShaderTests.create("gbuffers_terrain_cutout_mipped", terrainSource.orElseThrow(RuntimeException::new), renderTargets, baseline, 0.5F, IrisVertexFormats.TERRAIN, true);

		if (translucentSource != terrainSource) {
			this.terrainTranslucent = NewShaderTests.create("gbuffers_translucent", translucentSource.orElseThrow(RuntimeException::new), renderTargets, baseline, 0.0F, IrisVertexFormats.TERRAIN, true);
		} else {
			this.terrainTranslucent = this.terrainSolid;
		}

		int[] buffersToBeCleared = programSet.getPackDirectives().getBuffersToBeCleared().toIntArray();

		this.clearAltBuffers = renderTargets.createFramebufferWritingToAlt(buffersToBeCleared);
		this.clearMainBuffers = renderTargets.createFramebufferWritingToMain(buffersToBeCleared);

		// Don't clobber anything in texture unit 0. It probably won't cause issues, but we're just being cautious here.
		GlStateManager.activeTexture(GL20C.GL_TEXTURE2);

		// Create some placeholder PBR textures for now
		normals = new SingleColorTexture(127, 127, 255, 255);
		specular = new SingleColorTexture(0, 0, 0, 0);

		final int noiseTextureResolution = programSet.getPackDirectives().getNoiseTextureResolution();
		noise = new NoiseTexture(noiseTextureResolution, noiseTextureResolution);

		GlStateManager.activeTexture(GL20C.GL_TEXTURE0);

		this.shadowMapRenderer = new EmptyShadowMapRenderer(2048);
		this.compositeRenderer = new CompositeRenderer(programSet, renderTargets, shadowMapRenderer);
	}

	@SafeVarargs
	private static <T> Optional<T> first(Optional<T>... candidates) {
		for (Optional<T> candidate : candidates) {
			if (candidate.isPresent()) {
				return candidate;
			}
		}

		return Optional.empty();
	}

	@Override
	public void setPhase(WorldRenderingPhase phase) {
		this.phase = phase;
	}

	@Override
	public WorldRenderingPhase getPhase() {
		return phase;
	}

	@Override
	public void beginWorldRendering() {
		// Make sure we're using texture unit 0 for this.
		RenderSystem.activeTexture(GL15C.GL_TEXTURE0);

		Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
		renderTargets.resizeIfNeeded(main.textureWidth, main.textureHeight);

		clearMainBuffers.bind();
		RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
		RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);

		clearAltBuffers.bind();
		// Not clearing the depth buffer since there's only one of those and it was already cleared
		RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
		RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
	}

	@Override
	public void beginTranslucents() {
		// We need to copy the current depth texture so that depthtex1 and depthtex2 can contain the depth values for
		// all non-translucent content, as required.
		baseline.bind();
		GlStateManager.bindTexture(renderTargets.getDepthTextureNoTranslucents().getTextureId());
		GL20C.glCopyTexImage2D(GL20C.GL_TEXTURE_2D, 0, GL20C.GL_DEPTH_COMPONENT, 0, 0, renderTargets.getCurrentWidth(), renderTargets.getCurrentHeight(), 0);
	}

	@Override
	public void pushProgram(GbufferProgram program) {

	}

	@Override
	public void popProgram(GbufferProgram program) {

	}

	@Override
	public void finalizeWorldRendering() {
		compositeRenderer.renderAll();
	}

	@Override
	public boolean shouldDisableVanillaEntityShadows() {
		return true;
	}

	@Override
	public boolean shouldDisableDirectionalShading() {
		return true;
	}

	@Override
	public Shader getSkyBasic() {
		return skyBasic;
	}

	@Override
	public Shader getSkyBasicColor() {
		return skyBasicColor;
	}

	@Override
	public Shader getSkyTextured() {
		return skyTextured;
	}

	@Override
	public Shader getTerrain() {
		return terrainSolid;
	}

	@Override
	public Shader getTerrainCutout() {
		return terrainCutout;
	}

	@Override
	public Shader getTerrainCutoutMipped() {
		return terrainCutoutMipped;
	}

	@Override
	public Shader getTranslucent() {
		return terrainTranslucent;
	}

	@Override
	public void destroy() {
		// NB: If you forget this, shader reloads won't work!
		skyBasic.close();
		skyBasicColor.close();
		skyTextured.close();

		terrainSolid.close();
		terrainCutout.close();
		terrainCutoutMipped.close();

		if (terrainTranslucent != terrainSolid) {
			terrainTranslucent.close();
		}
	}

	@Override
	public float getSunPathRotation() {
		return sunPathRotation;
	}
}
