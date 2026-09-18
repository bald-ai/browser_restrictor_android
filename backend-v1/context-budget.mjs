import {fail} from './domain.mjs';

export const CHAT_CONTEXT_TOKENS = 100_000;

// Deliberately conservative: UTF-8 bytes bound text tokens without guessing a
// model tokenizer. Count the entire payload (including tools), plus framing and
// the full output allowance. This may retain less than 100k actual tokens.
export function contextUpperBound(body) {
 return Buffer.byteLength(JSON.stringify(body), 'utf8')
  + 1024 + body.input.length * 32 + (body.max_output_tokens || 0);
}

export function fitChatContext(body) {
 const input = [...body.input];
 const request = {...body, input};
 while (contextUpperBound(request) > CHAT_CONTEXT_TOKENS) {
  const currentUser = input.findLastIndex(item => item.role === 'user');
  fail(currentUser > 0,
   'This request exceeds the chat context limit. Please shorten the request or simplify long task instructions in Your setup. Manual controls are still available.', 413);
  // Remove an entire oldest exchange; never separate a tool call from its
  // result, or remove the current user request and fresh authoritative state.
  let nextUser = input.findIndex((item, index) => index > 0 && item.role === 'user');
  fail(nextUser > 0, 'This request exceeds the chat context limit. Please use a shorter request.', 413);
  input.splice(0, nextUser);
 }
 return request;
}
