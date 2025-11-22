let ws;
let nickname;

function join() {
    nickname = document.getElementById('nicknameInput').value.trim();
    if (!nickname) {
        alert('Por favor ingrese un nickname');
        return;
    }

    ws = new WebSocket('ws://localhost:8081');

    ws.onopen = function() {
        ws.send(JSON.stringify({type: 'join', nickname: nickname}));
        document.getElementById('loginScreen').classList.remove('active');
        document.getElementById('chatScreen').classList.add('active');
        document.getElementById('userNickname').textContent = nickname;
    };

    ws.onmessage = function(event) {
        const data = JSON.parse(event.data);
        if (data.type === 'message') {
            addMessage(data.sender, data.message, data.timestamp);
        } else if (data.type === 'userlist') {
            updateUserList(data.users);
        }
    };

    ws.onerror = function() {
        alert('Error de conexión');
    };

    ws.onclose = function() {
        alert('Desconectado del servidor');
    };
}

function sendMessage() {
    const input = document.getElementById('messageInput');
    const message = input.value.trim();
    if (message && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({type: 'message', message: message}));
        input.value = '';
    }
}

document.addEventListener('DOMContentLoaded', function() {
    document.getElementById('messageInput').addEventListener('keypress', function(e) {
        if (e.key === 'Enter') sendMessage();
    });
});

function addMessage(sender, message, timestamp) {
    const messagesDiv = document.getElementById('messages');
    const messageDiv = document.createElement('div');

    if (sender === 'system') {
        messageDiv.className = 'message system';
        messageDiv.textContent = message;
    } else {
        messageDiv.className = sender === nickname ? 'message own' : 'message other';

        if (sender !== nickname) {
            const senderDiv = document.createElement('div');
            senderDiv.className = 'message-sender';
            senderDiv.textContent = sender;
            messageDiv.appendChild(senderDiv);
        }

        const textDiv = document.createElement('div');
        textDiv.textContent = message;
        messageDiv.appendChild(textDiv);

        const timeDiv = document.createElement('div');
        timeDiv.className = 'message-time';
        timeDiv.textContent = new Date(timestamp).toLocaleTimeString();
        messageDiv.appendChild(timeDiv);
    }

    messagesDiv.appendChild(messageDiv);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

function updateUserList(users) {
    const usersDiv = document.getElementById('users');
    usersDiv.innerHTML = '';
    users.forEach(user => {
        const userDiv = document.createElement('div');
        userDiv.className = 'user-item';
        userDiv.textContent = user;
        usersDiv.appendChild(userDiv);
    });
}

