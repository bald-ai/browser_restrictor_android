import {configSchema,taskSchema} from './domain.mjs';
const obj=properties=>({type:'object',properties,required:Object.keys(properties),additionalProperties:false});
const tag=value=>({type:'string',enum:[value]});
const settingOperations=Object.entries(configSchema.properties.settings.properties).map(([field,value])=>obj({action:tag('set_setting'),field:tag(field),value}));
const taskOperations=Object.entries(taskSchema.properties).filter(([k])=>k!=='id').map(([field,value])=>obj({action:tag('set_task_field'),taskId:{type:'string'},field:tag(field),value}));
export const changeSchema={anyOf:[...settingOperations,...taskOperations,obj({action:tag('add_task'),task:taskSchema}),obj({action:tag('remove_task'),taskId:{type:'string'}})]};
