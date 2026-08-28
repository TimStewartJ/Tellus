/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.render.nativeReadiness;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the readiness mask to compatible Iris DH shader-pack programs.
 * Unknown shader layouts are returned unchanged so their existing clipping
 * remains the safe fallback.
 */
public final class IrisDhShaderReadinessPatcher
{
	public static final String READINESS_SAMPLER_NAME = "dhNativeReadinessTexture";

	private static final String PATCH_MARKER = "maxOverdrawDistance";
	private static final int MAX_DISCARD_SEARCH_DISTANCE = 2048;
	private static final Pattern MAIN_FUNCTION_PATTERN =
		Pattern.compile("\\bvoid\\s+main\\s*\\([^)]*\\)\\s*\\{");

	private static final String VERTEX_DECLARATION =
		"varying vec2 dhNativeReadinessRelativeBlockPos;\n";

	private static final String FRAGMENT_SUPPORT = String.join("\n",
		"varying vec2 dhNativeReadinessRelativeBlockPos;",
		"uniform sampler2D " + READINESS_SAMPLER_NAME + ";",
		"uniform ivec2 dhNativeReadinessMaskMinOffset;",
		"uniform ivec2 dhNativeReadinessMaskSize;",
		"uniform vec2 dhNativeReadinessCameraSubChunk;",
		"uniform int dhNativeReadinessEnabled;",
		"",
		"float dhNativeReadinessBayer4x4(vec2 position)",
		"{",
		"    int x = int(mod(position.x, 4.0));",
		"    int y = int(mod(position.y, 4.0));",
		"    float values[16] = float[16](",
		"        0.0, 8.0, 2.0, 10.0,",
		"        12.0, 4.0, 14.0, 6.0,",
		"        3.0, 11.0, 1.0, 9.0,",
		"        15.0, 7.0, 13.0, 5.0",
		"    );",
		"    return values[y * 4 + x] / 16.0;",
		"}",
		"",
		"float dhNativeReadinessAtFragment()",
		"{",
		"    if (dhNativeReadinessEnabled == 0)",
		"    {",
		"        return 1.0;",
		"    }",
		"",
		"    ivec2 relativeChunk = ivec2(floor(",
		"        (dhNativeReadinessRelativeBlockPos + dhNativeReadinessCameraSubChunk) / 16.0));",
		"    ivec2 texel = relativeChunk - dhNativeReadinessMaskMinOffset;",
		"    if (any(lessThan(texel, ivec2(0)))",
		"        || any(greaterThanEqual(texel, dhNativeReadinessMaskSize)))",
		"    {",
		"        return 1.0;",
		"    }",
		"",
		"    return texelFetch(" + READINESS_SAMPLER_NAME + ", texel, 0).r;",
		"}",
		"",
		"bool dhNativeReadinessShouldDiscard()",
		"{",
		"    return dhNativeReadinessAtFragment() > dhNativeReadinessBayer4x4(gl_FragCoord.xy);",
		"}",
		"");



	private IrisDhShaderReadinessPatcher() { }

	public static PatchedShaders tryPatch(String vertexSource, String fragmentSource)
	{
		if (vertexSource == null || fragmentSource == null
			|| fragmentSource.contains("dhNativeReadinessShouldDiscard"))
		{
			return PatchedShaders.notPatched(vertexSource, fragmentSource);
		}

		String patchedFragment = replaceOverdrawDiscard(fragmentSource);
		if (patchedFragment == null)
		{
			return PatchedShaders.notPatched(vertexSource, fragmentSource);
		}

		String patchedVertex = addVertexOutput(vertexSource);
		if (patchedVertex == null)
		{
			return PatchedShaders.notPatched(vertexSource, fragmentSource);
		}

		patchedFragment = insertBeforeMain(patchedFragment, FRAGMENT_SUPPORT);
		if (patchedFragment == null)
		{
			return PatchedShaders.notPatched(vertexSource, fragmentSource);
		}

		return new PatchedShaders(patchedVertex, patchedFragment, true);
	}

	private static String addVertexOutput(String source)
	{
		Matcher mainMatcher = MAIN_FUNCTION_PATTERN.matcher(source);
		if (!mainMatcher.find())
		{
			return null;
		}

		String withDeclaration = insertBeforeMain(source, VERTEX_DECLARATION);
		if (withDeclaration == null)
		{
			return null;
		}

		mainMatcher = MAIN_FUNCTION_PATTERN.matcher(withDeclaration);
		if (!mainMatcher.find())
		{
			return null;
		}

		return withDeclaration.substring(0, mainMatcher.end())
			+ "\n    dhNativeReadinessRelativeBlockPos = gl_Vertex.xz;"
			+ withDeclaration.substring(mainMatcher.end());
	}

	private static String replaceOverdrawDiscard(String source)
	{
		int markerIndex = source.indexOf(PATCH_MARKER);
		if (markerIndex < 0)
		{
			return null;
		}

		int discardIndex = source.indexOf("discard", markerIndex);
		if (discardIndex < 0 || discardIndex - markerIndex > MAX_DISCARD_SEARCH_DISTANCE)
		{
			return null;
		}

		int discardSemicolon = source.indexOf(';', discardIndex);
		if (discardSemicolon < 0)
		{
			return null;
		}

		int replacementEnd = discardSemicolon + 1;
		int nextToken = skipWhitespace(source, replacementEnd);
		boolean followedByReturn = source.startsWith("return", nextToken);
		if (followedByReturn)
		{
			int returnSemicolon = source.indexOf(';', nextToken + "return".length());
			if (returnSemicolon < 0)
			{
				return null;
			}
			replacementEnd = returnSemicolon + 1;
		}

		String replacement = followedByReturn
			? "if (dhNativeReadinessShouldDiscard()) { discard; return; }"
			: "if (dhNativeReadinessShouldDiscard()) { discard; }";

		return source.substring(0, discardIndex)
			+ replacement
			+ source.substring(replacementEnd);
	}

	private static String insertBeforeMain(String source, String content)
	{
		Matcher mainMatcher = MAIN_FUNCTION_PATTERN.matcher(source);
		if (!mainMatcher.find())
		{
			return null;
		}

		return source.substring(0, mainMatcher.start())
			+ content + "\n"
			+ source.substring(mainMatcher.start());
	}

	private static int skipWhitespace(String source, int startIndex)
	{
		int index = startIndex;
		while (index < source.length() && Character.isWhitespace(source.charAt(index)))
		{
			index++;
		}
		return index;
	}



	public static final class PatchedShaders
	{
		public final String vertexSource;
		public final String fragmentSource;
		public final boolean patched;

		private PatchedShaders(String vertexSource, String fragmentSource, boolean patched)
		{
			this.vertexSource = vertexSource;
			this.fragmentSource = fragmentSource;
			this.patched = patched;
		}

		private static PatchedShaders notPatched(String vertexSource, String fragmentSource)
		{
			return new PatchedShaders(vertexSource, fragmentSource, false);
		}
	}

}
