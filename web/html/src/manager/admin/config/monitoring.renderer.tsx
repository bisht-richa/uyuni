import * as React from "react";

import SpaRenderer from "core/spa/spa-renderer";

import MonitoringAdmin from "./monitoring-admin";

export const renderer = (id: string, props: {}) => {
  SpaRenderer.renderNavigationReact(<MonitoringAdmin />, document.getElementById(id));
};
