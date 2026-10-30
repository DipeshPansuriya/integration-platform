import blank from './blank-api-to-api.json';
import nested from './nested-appointment-showcase.json';

export type Template = Record<string, unknown>;
export const templates: Template[] = [blank as Template, nested as Template];
export { default as blankApiToApiTemplate } from './blank-api-to-api.json';
export { default as nestedAppointmentShowcaseTemplate } from './nested-appointment-showcase.json';
