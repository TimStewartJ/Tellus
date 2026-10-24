# Recorded inland hydrology fixtures

These are fixed, offline samples for generation regression tests, not exact
replays of a user's saved world. The map geometry is from Overture Maps' water
layer (including OpenStreetMap-derived features). Elevation samples are from
Mapterhorn Terrarium tiles, resampled on a 16-block grid at 1:1 world scale.

- `huentitan.json.gz`: Río Santiago / Río Verde near Barranca de Huentitán,
  taken from the local Tellus map and elevation cache (elevation zoom 12).
- `niagara.json.gz`: Niagara Falls, obtained from the published Overture tile
  release and Mapterhorn elevation zoom 13 during the investigation.

Tests use a 30-metre source-resolution hint to exercise coarse-DEM handling.
Elevations are stored in metres; the fixture sampler applies Tellus's Mercator
height correction and a sea-level offset. Out-of-capture sampling is clamped;
assertions are restricted to the interior with a planning halo.

Upstream data and attribution:
- Overture Maps: https://docs.overturemaps.org/attribution/
- OpenStreetMap contributors (ODbL): https://www.openstreetmap.org/copyright
- Mapterhorn: https://mapterhorn.com/

No fixture test performs a download or changes an installed game's cache.
