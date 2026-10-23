---
title: Welcome
---
# SolarNode Filter Simulator

This handbook provides guides and reference documentation for the
[SolarNetwork](https://solarnetwork.net/) SolarNode Filter Simulator. This is a browser-based tool
to help create, edit, view, and debug the SolarNode [Datum Filter][datum-filters] processing
runtime.

Datum Filters are services in SolarNode that can filter, augment, and modify [datum][datum] in
real-time, as they are created. Datum Filters can also create new datum and interact with
[controls][controls]. They are very flexible and powerful, but as you add more than just a few
filters it can be cumbersome to keep track of what all of them are doing and reason about their
effects on the datum processing pipeline.

This tool helps you develop and maintain complex filter configurations using an intuitive,
interactive flow-chart style diagram. You can drag and drop filters onto the diagram, inspect and
modify their settings, and then simulate sending datum through them. You can even import the
settings configuration from a real SolarNode, make changes, test the changes, export the settings
back out when you are happy, and finally import the updated settings back into your SolarNode.

![Using SolarNode Filter Simulator](./images/example-flow@2x.png){width=2131}


[controls]: https://solarnetwork.github.io/solarnode-handbook/users/controls/
[datum]: https://solarnetwork.github.io/solarnode-handbook/users/datum/
[datum-filters]: https://solarnetwork.github.io/solarnode-handbook/users/datum-filters/
