import {readFileSync} from 'node:fs';

// One maintained product guide, shipped inside the backend image. Topic IDs are
// allowlisted by both the tool schema and lookup; no user-supplied file paths.
export const appOverview = readFileSync(new URL('./docs/app-overview.md', import.meta.url), 'utf8').trim();
const guide = readFileSync(new URL('./docs/app-guide.md', import.meta.url), 'utf8');
const sections = [...guide.matchAll(/^## ([a-z_]+): (.+)\n([\s\S]*?)(?=^## |$(?![\s\S]))/gm)];
export const docs = Object.fromEntries(sections.map(([,id,title,body]) => [id, `# ${title}\n\n${body.trim()}`]));
if (!sections.length || Object.keys(docs).length !== sections.length || sections.some(([,id,,body]) => id === 'index' || id === 'history' || !body.trim())) {
 throw new Error('Invalid app guide topic structure');
}
export const documentationIndex = sections.map(([,id,title]) => `- ${id}: ${title}`).join('\n');
export const documentationTopics = ['index', ...Object.keys(docs)];
export function readAppDocumentation(topic) {
 if (topic === 'index') return `App documentation topics (read_details):\n${documentationIndex}\n- history: Actual uploaded device statistics, not documentation.\n\nUse fresh app context for the user's actual settings. Defaults in the guide are examples of factory configuration.`;
 if (typeof topic !== 'string' || !Object.hasOwn(docs, topic)) throw new Error('Unknown documentation topic. Use read_details with topic index.');
 return docs[topic];
}
