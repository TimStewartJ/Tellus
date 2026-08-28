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

package tests;

import com.seibel.distanthorizons.core.render.nativeReadiness.IrisDhShaderReadinessPatcher;
import org.junit.Assert;
import org.junit.Test;

public class IrisDhShaderReadinessPatcherTest
{
	@Test
	public void patchesCompatibleOverdrawDiscard()
	{
		String vertex = "#version 330 compatibility\n"
			+ "void main() { gl_Position = gl_ProjectionMatrix * gl_Vertex; }\n";
		String fragment = "#version 330 compatibility\n"
			+ "void main() {\n"
			+ "  float maxOverdrawDistance = far;\n"
			+ "  if (length(pos.xyz) < maxOverdrawDistance) { discard; return; }\n"
			+ "  if (alpha == 0.0) { discard; }\n"
			+ "}\n";

		IrisDhShaderReadinessPatcher.PatchedShaders result =
			IrisDhShaderReadinessPatcher.tryPatch(vertex, fragment);

		Assert.assertTrue(result.patched);
		Assert.assertTrue(result.vertexSource.contains(
			"dhNativeReadinessRelativeBlockPos = gl_Vertex.xz;"));
		Assert.assertTrue(result.fragmentSource.contains(
			"if (dhNativeReadinessShouldDiscard()) { discard; return; }"));
		Assert.assertTrue(result.fragmentSource.contains(
			"if (alpha == 0.0) { discard; }"));
		Assert.assertTrue(result.fragmentSource.contains(
			"uniform sampler2D " + IrisDhShaderReadinessPatcher.READINESS_SAMPLER_NAME));
	}

	@Test
	public void leavesUnknownShaderLayoutUnchanged()
	{
		String vertex = "#version 330 compatibility\nvoid main() { }\n";
		String fragment = "#version 330 compatibility\nvoid main() { discard; }\n";

		IrisDhShaderReadinessPatcher.PatchedShaders result =
			IrisDhShaderReadinessPatcher.tryPatch(vertex, fragment);

		Assert.assertFalse(result.patched);
		Assert.assertSame(vertex, result.vertexSource);
		Assert.assertSame(fragment, result.fragmentSource);
	}

	@Test
	public void refusesPartialPatchWithoutVertexMain()
	{
		String vertex = "#version 330 compatibility\n";
		String fragment = "#version 330 compatibility\n"
			+ "void main() { float maxOverdrawDistance = far; discard; return; }\n";

		IrisDhShaderReadinessPatcher.PatchedShaders result =
			IrisDhShaderReadinessPatcher.tryPatch(vertex, fragment);

		Assert.assertFalse(result.patched);
		Assert.assertSame(vertex, result.vertexSource);
		Assert.assertSame(fragment, result.fragmentSource);
	}

}
