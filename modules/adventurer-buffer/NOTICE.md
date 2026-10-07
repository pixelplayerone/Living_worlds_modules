# Attribution

New Adventurer Buffer code: Khaos, GPL-3.0-or-later, prepared with AI assistance.

Living Worlds module API and Interlude NPC/skill data: Teravibes / L2jMobius and their contributors. Appearance 30598 is reused through displayId; no client asset is redistributed. The NPC XML copies the stock 30598 server template with a new reserved ID/name and immobile status. Stock skill definitions are referenced and remain unchanged. Relevant source: https://github.com/Teravibes/L2-Living-Worlds .

Sateriok's GPL-3.0 Preset Buffer 1.0.0 was inspected as a design reference for module packaging, displayId, standard game UI and effect APIs: https://github.com/Teravibes/L2-Living-Worlds-Modules-/tree/main/modules/preset-buffer . The supported skill IDs/ranks are adapted from its catalog, deduplicated and checked against native XML. Native names are resolved at runtime. Its Java implementation and preset arrays are not bundled. The adapted catalog material is GPL-3.0-only; the combined distribution is conveyed under GPL version 3. No endorsement is implied.

The following permissive notice is retained for L2jMobius material:

/*
 * Copyright (c) 2013 L2jMobius
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
