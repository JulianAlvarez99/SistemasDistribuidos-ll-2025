Esta aplicacion implementa arquitectura Cliente-Servidor (Primario-Backup) donde un componente central (CoordinatorServer) recibe, ordena y distribuye las actualizaciones. Pero no mantiene la persistencia de los datos.

### Flujo de ejecucion 
- Iniciar CoordinatorServer 

- Iniciar tantas instacias quiera de DistributedListClient

- Ingresar Nickname y conectarse

- Configurar parametros

- Enviar items a agregar

### Logica de trabajo

Un DistributedListClient se conecta al CoordinatorServer vía TCP.

- Envía un mensaje CONNECT|<nickname>.

- El servidor lo registra y le envía la lista de ítems actual y completa (INITIAL_LIST|sequence_num|item1,item2,...), que mantiene en memoria. Si es el primer cliente, la lista está vacía.

Adición de Ítems:

- Consistencia Estricta: El cliente envía inmediatamente el mensaje encriptado ADD_ITEM|STRICT|...|newItem al servidor.

- Consistencia Continua: El cliente almacena los ítems en un buffer local y envía el mensaje encriptado al servidor mediante un Timer cuando se cumple el delay configurado.

En ambos casos, el servidor recibe los mensajes encriptados. Una vez desencriptados, interpreta el comando ADD_ITEM, lo agrega a su lista maestra, le asigna un número de secuencia monotónicamente creciente, y difunde el mensaje nuevamente encriptado (broadcast) a todos los clientes conectados con el mensaje UPDATE_LIST|<sequence_num>|newItem.

Actualización de Clientes:

- Cada cliente recibe el mensaje encriptado y lo decifra obteniendo UPDATE_LIST, verifica el número de secuencia para mantener el orden y actualiza su lista local.

En lo que es materia de seguridad seguridad:

- Toda la comunicación entre cliente y servidor es cifrada. Antes de enviar un mensaje, se encripta con AES/CBC/PKCS5Padding usando una clave simétrica precompartida. Al recibirlo, se desencripta. La clase CryptoUtils encapsula esta lógica.

Modo Avión tolerante a fallos:

- Al activar "Modo Avión", el cliente establece un flag isAirplaneMode = true e ignora los mensajes UPDATE_LIST entrantes, pero sigue registrando el último número de secuencia procesado (lastProcessedSequenceNumber).

- Al desactivarlo, el cliente envía al servidor un mensaje SYNC_REQUEST|<lastProcessedSequenceNumber>.

- El servidor busca en su historial de mensajes (un Map<Integer, String>) todos los mensajes con un número de secuencia mayor al recibido y se los reenvía al cliente, que los procesa en orden para ponerse al día.

Esta arquitectura no almacena en disco. El estado se pierde al cerrar las aplicaciones. Si el coordinador se mantuviese conectado pero sin nodos, realiza una limpieza de los registros y queda en blanco. Si se llegase a conectar un nuevo nodo, y fuese el primero en conectarse incia una nueva lista.