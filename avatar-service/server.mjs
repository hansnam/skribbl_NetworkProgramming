import { toPng } from '@dicebear/converter';
import { Style, Avatar } from '@dicebear/core';
import definition from '@dicebear/styles/bottts-neutral.json' with { type: 'json' };
import { WebSocket, WebSocketServer } from 'ws';

const style = new Style(definition);
const cache = new Map();

async function generatePng(imageId) {
    const avatar = new Avatar(style, {seed: imageId});
    const svg = avatar.toString();
    const png = toPng(svg, {size: 64});
    return Buffer.from(await png.toArrayBuffer());
}

function getPng(imageId) {
    let pending = cache.get(imageId);
    if (!pending) {
        pending = generatePng(imageId).catch(error => {
            cache.delete(imageId);
            throw error;
        });
        cache.set(imageId, pending);
    }
    return pending;
}

const host = process.env.SKRIBBL_AVATAR_HOST?.trim() || '127.0.0.1';
const server = new WebSocketServer({
    host,
    port: 8787,
    maxPayload: 1024,
    perMessageDeflate: false
});

server.on('connection', async (socket, request) => {
    socket.on('error', error => console.error(error.message));

    const match = /^\/avatars\/(robot-[1-6])$/.exec(request.url ?? '');
    if (!match) {
        socket.close(1008, 'Unknown avatar');
        return;
    }

    try {
        const png = await getPng(match[1]);
        if (socket.readyState === WebSocket.OPEN) {
            socket.send(png, {binary: true}, error => {
                if (error) {
                    socket.terminate();
                } else {
                    socket.close(1000);
                }
            });
        }
    } catch (e) {
        console.error(e.message);
        if (socket.readyState === WebSocket.OPEN) {
            socket.close(1011, 'Avatar unavailable');
        }
    }
})

server.on('listening', () => console.log(`Avatar WebSocket: ws://${host}:8787`));
