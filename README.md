<p align="center">
  <img src="showcase/logo.svg" alt="Auto Drill logo" width="128">
</p>

<h1 align="center">Auto Drill</h1>

<p align="center">
  <a href="https://github.com/Kavun-Sama/auto-drill-v2/actions/workflows/commitTest.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/Kavun-Sama/auto-drill-v2/commitTest.yml?branch=master&label=build"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/Kavun-Sama/auto-drill-v2"></a>
  <img alt="Mindustry" src="https://img.shields.io/badge/Mindustry-v8%20build%20160.7-F5A623">
  <a href="https://github.com/Kavun-Sama/auto-drill-v2/commits/master"><img alt="Last commit" src="https://img.shields.io/github/last-commit/Kavun-Sama/auto-drill-v2"></a>
</p>

<p align="center">
  Fork of <a href="https://github.com/Pointifix/AutoDrill">Pointifix/AutoDrill</a> updated for Mindustry v8 build 160.7.
</p>

Mindustry Mod which adds tools to automatically fill a resource patch with drills.

Enable the mod by the activation key (default `H`) or by clicking the activation button next to the minimap.
When activated, clicking on any tile will display a toolbar with the drills the current tile can be mined with.
The mechanical drill and pneumatic drill buttons fill the resource patch with drills, and a bridge layout facing in the picked direction.

![](showcase/bridge-drill.gif)

The laser and airblast drill buttons fill the resource patch with drills and places water extractors and power node adjacent to each drill if possible.

![](showcase/optimization-drill.gif)

For Erekir the mod fills the whole wall deposit with plasma bores, powers them with beam nodes and joins them with a duct line. The picked direction is the side the ducts leave on.

![](showcase/wall-drill.gif)

The mod has several configuration options.

- **Activation Key** 
  
  The key with which the mod is toggled

- **Display Toggle Button**
  
  Whether to display the toggle button
  
- **Max Tiles** 
  
  The maximum number of tiles that are flood filled and later filled with drills, highly effects performance

- **Minimum Ores** 
  
  The minimum amount of resource tiles any drill must cover

- **Optimization Quality** 
  
  The optimization algorithm for filling an area with drills iterates the whole possibility space, starting with tiles which yield the highest mining rate. The optimization quality parameter determines how early this search is terminated. Increasing this number may produce better results in trade of computation speed.

- **Place Water Extractors and Power Nodes** 
  
  Whether to place water extractors and power nodes next to the drills

![](showcase/settings.png)
