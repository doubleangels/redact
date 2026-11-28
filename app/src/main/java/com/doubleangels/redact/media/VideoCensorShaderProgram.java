package com.doubleangels.redact.media;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The GL half of {@link VideoCensorEffect}: copies each frame, then covers every area active at
 * that frame's time. Kept thin, with the geometry in {@link VideoCensorLayout}, since it only runs
 * on a device.
 *
 * <p>Pixelate and Blur take two passes per area. The first renders one pixel per block into a
 * small texture: the block's average (from an even 8 x 8 grid of filtered samples across it) plus
 * that area's noise. The second draws the area from those block colours alone, square for Pixelate
 * and smoothly blended for Blur, so nothing finer than a block reaches the output. Black is one
 * solid fill.
 *
 * <p>Image coordinates count down from the frame's top; OpenGL's run up from its bottom, so the
 * shaders flip y where they meet. Block textures hold their rows top first.
 */
@OptIn(markerClass = UnstableApi.class)
final class VideoCensorShaderProgram extends BaseGlShaderProgram {

    private static final String VERTEX_SHADER = ""
            + "attribute vec4 aFramePosition;\n"
            + "varying vec2 vTexCoords;\n"
            + "void main() {\n"
            + "  gl_Position = aFramePosition;\n"
            + "  vTexCoords = (aFramePosition.xy + 1.0) * 0.5;\n"
            + "}\n";

    private static final String COPY_SHADER = ""
            + "precision highp float;\n"
            + "uniform sampler2D uTexSampler;\n"
            + "varying vec2 vTexCoords;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(uTexSampler, vTexCoords);\n"
            + "}\n";

    /** One output pixel per block: the block's average plus its noise. */
    private static final String AVERAGE_SHADER = ""
            + "precision highp float;\n"
            + "uniform sampler2D uTexSampler;\n"
            + "uniform sampler2D uNoiseSampler;\n"
            + "uniform vec2 uFrameSize;\n"
            // left, top, right, bottom of the block grid, in image pixels
            + "uniform vec4 uGrid;\n"
            + "uniform float uBlock;\n"
            + "uniform vec2 uTextureBlocks;\n"
            + "uniform float uNoise;\n"
            + "uniform float uMaxValue;\n"
            + "void main() {\n"
            + "  vec2 cell = floor(gl_FragCoord.xy);\n"
            // A moving area's grid can start off the frame: only the part on it is averaged.
            + "  vec2 start = uGrid.xy + cell * uBlock;\n"
            + "  vec2 lo = max(start, vec2(0.0));\n"
            + "  vec2 hi = min(start + uBlock, uGrid.zw);\n"
            + "  vec2 step = (hi - lo) / 8.0;\n"
            + "  vec4 sum = vec4(0.0);\n"
            + "  for (int j = 0; j < 8; j++) {\n"
            + "    for (int i = 0; i < 8; i++) {\n"
            + "      vec2 p = lo + (vec2(float(i), float(j)) + 0.5) * step;\n"
            + "      sum += texture2D(uTexSampler, vec2(p.x / uFrameSize.x, 1.0 - p.y / uFrameSize.y));\n"
            + "    }\n"
            + "  }\n"
            + "  vec4 average = sum / 64.0;\n"
            + "  vec3 offset = texture2D(uNoiseSampler, (cell + 0.5) / uTextureBlocks).rgb * 255.0 - uNoise;\n"
            + "  gl_FragColor = vec4(clamp(average.rgb + offset / 255.0, 0.0, uMaxValue), average.a);\n"
            + "}\n";

    /** Draws an area from its block colours: squares for Pixelate, a smooth blend for Blur. */
    private static final String BLOCK_SHADER = ""
            + "precision highp float;\n"
            + "uniform sampler2D uBlockSampler;\n"
            + "uniform vec2 uFrameSize;\n"
            + "uniform vec2 uGridOrigin;\n"
            + "uniform float uBlock;\n"
            + "uniform vec2 uBlocks;\n"
            + "uniform vec2 uTextureBlocks;\n"
            + "uniform int uBlur;\n"
            + "vec4 cellColour(vec2 cell) {\n"
            + "  return texture2D(uBlockSampler, (cell + 0.5) / uTextureBlocks);\n"
            + "}\n"
            + "void main() {\n"
            + "  vec2 local = vec2(gl_FragCoord.x, uFrameSize.y - gl_FragCoord.y) - uGridOrigin;\n"
            + "  if (uBlur == 0) {\n"
            + "    gl_FragColor = cellColour(clamp(floor(local / uBlock), vec2(0.0), uBlocks - 1.0));\n"
            + "  } else {\n"
            + "    vec2 f = clamp(local / uBlock - 0.5, vec2(0.0), uBlocks - 1.0);\n"
            + "    vec2 c0 = floor(f);\n"
            + "    vec2 c1 = min(uBlocks - 1.0, c0 + 1.0);\n"
            + "    vec2 t = f - c0;\n"
            + "    t = t * t * (3.0 - 2.0 * t);\n"
            + "    vec4 upper = mix(cellColour(c0), cellColour(vec2(c1.x, c0.y)), t.x);\n"
            + "    vec4 lower = mix(cellColour(vec2(c0.x, c1.y)), cellColour(c1), t.x);\n"
            + "    gl_FragColor = mix(upper, lower, t.y);\n"
            + "  }\n"
            + "}\n";

    private static final String FILL_SHADER = ""
            + "precision mediump float;\n"
            + "void main() {\n"
            + "  gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n"
            + "}\n";

    private static final String POSITION = "aFramePosition";

    @NonNull
    private final VideoCensorEffect effect;
    private final boolean useHdr;
    private final GlProgram copyProgram;
    private final GlProgram averageProgram;
    private final GlProgram blockProgram;
    private final GlProgram fillProgram;

    private List<VideoCensorLayout.Track> tracks = List.of();
    private int[] blockTextures = new int[0];
    private int[] blockFbos = new int[0];
    private int[] noiseTextures = new int[0];
    private int width;
    private int height;

    VideoCensorShaderProgram(@NonNull VideoCensorEffect effect, boolean useHdr)
            throws VideoFrameProcessingException {
        super(useHdr, 1);
        this.effect = effect;
        this.useHdr = useHdr;
        try {
            copyProgram = new GlProgram(VERTEX_SHADER, COPY_SHADER);
            averageProgram = new GlProgram(VERTEX_SHADER, AVERAGE_SHADER);
            blockProgram = new GlProgram(VERTEX_SHADER, BLOCK_SHADER);
            fillProgram = new GlProgram(VERTEX_SHADER, FILL_SHADER);
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
        float[] wholeFrame = GlUtil.getNormalizedCoordinateBounds();
        copyProgram.setBufferAttribute(POSITION, wholeFrame, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
        averageProgram.setBufferAttribute(POSITION, wholeFrame, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
    }

    @NonNull
    @Override
    public Size configure(int inputWidth, int inputHeight) throws VideoFrameProcessingException {
        try {
            deleteAreaTextures();
            width = inputWidth;
            height = inputHeight;
            tracks = effect.tracks(inputWidth, inputHeight);
            blockTextures = new int[tracks.size()];
            blockFbos = new int[tracks.size()];
            noiseTextures = new int[tracks.size()];
            for (int i = 0; i < tracks.size(); i++) {
                VideoCensorLayout.Track track = tracks.get(i);
                if (track.effect == CensorEffects.Effect.BLACK
                        || track.textureCols() <= 0 || track.textureRows() <= 0) {
                    continue;
                }
                // Sized for the largest grid, which every frame of a moving area fits.
                blockTextures[i] = GlUtil.createTexture(track.textureCols(), track.textureRows(), useHdr);
                GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, blockTextures[i], GLES20.GL_NEAREST);
                blockFbos[i] = GlUtil.createFboForTexture(blockTextures[i]);
                noiseTextures[i] = GlUtil.generateTexture();
                GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, noiseTextures[i], GLES20.GL_NEAREST);
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, track.textureCols(),
                        track.textureRows(), 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                        ByteBuffer.wrap(track.noiseRgba()));
                GlUtil.checkGlError();
            }
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
        return new Size(inputWidth, inputHeight);
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
        int[] binding = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, binding, 0);
        int outputFbo = binding[0];
        try {
            copyProgram.use();
            copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
            copyProgram.bindAttributesAndUniforms();
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            for (int i = 0; i < tracks.size(); i++) {
                VideoCensorLayout.Track track = tracks.get(i);
                if (!effect.covers(track.regionIndex, presentationTimeUs)) {
                    continue;
                }
                VideoCensorLayout.Area area = track.at(presentationTimeUs);
                if (area.effect != CensorEffects.Effect.BLACK && (area.cols <= 0 || area.rows <= 0)) {
                    continue;
                }
                if (area.effect == CensorEffects.Effect.BLACK) {
                    fillProgram.use();
                    fillProgram.setBufferAttribute(POSITION, area.drawQuad(width, height),
                            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
                    fillProgram.bindAttributesAndUniforms();
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    continue;
                }
                float[] blocks = {area.cols, area.rows};
                float[] textureBlocks = {area.textureCols, area.textureRows};
                GlUtil.focusFramebufferUsingCurrentContext(blockFbos[i], area.cols, area.rows);
                averageProgram.use();
                averageProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                averageProgram.setSamplerTexIdUniform("uNoiseSampler", noiseTextures[i], 1);
                averageProgram.setFloatsUniform("uFrameSize", new float[] {width, height});
                averageProgram.setFloatsUniform("uGrid", new float[] {
                        area.gridLeft, area.gridTop, area.gridRight, area.gridBottom});
                averageProgram.setFloatUniform("uBlock", area.block);
                averageProgram.setFloatsUniform("uTextureBlocks", textureBlocks);
                averageProgram.setFloatUniform("uNoise", CensorBlocks.NOISE);
                averageProgram.setFloatUniform("uMaxValue", useHdr ? Float.MAX_VALUE : 1f);
                averageProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                GlUtil.focusFramebufferUsingCurrentContext(outputFbo, width, height);
                blockProgram.use();
                blockProgram.setBufferAttribute(POSITION, area.drawQuad(width, height),
                        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
                blockProgram.setSamplerTexIdUniform("uBlockSampler", blockTextures[i], 0);
                blockProgram.setFloatsUniform("uFrameSize", new float[] {width, height});
                blockProgram.setFloatsUniform("uGridOrigin", new float[] {area.gridLeft, area.gridTop});
                blockProgram.setFloatUniform("uBlock", area.block);
                blockProgram.setFloatsUniform("uBlocks", blocks);
                blockProgram.setFloatsUniform("uTextureBlocks", textureBlocks);
                blockProgram.setIntUniform("uBlur", area.effect == CensorEffects.Effect.BLUR ? 1 : 0);
                blockProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }
            GlUtil.checkGlError();
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e, presentationTimeUs);
        }
    }

    @Override
    public void release() throws VideoFrameProcessingException {
        super.release();
        try {
            deleteAreaTextures();
            copyProgram.delete();
            averageProgram.delete();
            blockProgram.delete();
            fillProgram.delete();
        } catch (GlUtil.GlException e) {
            throw new VideoFrameProcessingException(e);
        }
    }

    private void deleteAreaTextures() throws GlUtil.GlException {
        for (int i = 0; i < blockTextures.length; i++) {
            if (blockFbos[i] != 0) {
                GlUtil.deleteFbo(blockFbos[i]);
            }
            if (blockTextures[i] != 0) {
                GlUtil.deleteTexture(blockTextures[i]);
            }
            if (noiseTextures[i] != 0) {
                GlUtil.deleteTexture(noiseTextures[i]);
            }
        }
        blockTextures = new int[0];
        blockFbos = new int[0];
        noiseTextures = new int[0];
    }
}
