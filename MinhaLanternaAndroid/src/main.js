
import './style.css';
import { Capacitor } from '@capacitor/core';
import { Torch } from '@capawesome/capacitor-torch';

const app = document.querySelector('#app');

let ligada = false;
let ocupado = false;

app.innerHTML = `
  <main class="cartao">
    <h1>Minha Lanterna</h1>

    <div class="icone" id="icone">🔦</div>

    <p class="status" id="status">
      LANTERNA DESLIGADA
    </p>

    <button id="botao" type="button">
      ⚡ LIGAR LANTERNA
    </button>

    <p class="mensagem" id="mensagem" role="status">
      Pronta para usar.
    </p>
  </main>
`;

const botao = document.querySelector('#botao');
const status = document.querySelector('#status');
const icone = document.querySelector('#icone');
const mensagem = document.querySelector('#mensagem');

function atualizarInterface() {
  document.body.classList.toggle('ligada', ligada);

  status.textContent = ligada
    ? 'LANTERNA LIGADA'
    : 'LANTERNA DESLIGADA';

  icone.textContent = ligada ? '💡' : '🔦';

  botao.textContent = ligada
    ? '⏻ DESLIGAR LANTERNA'
    : '⚡ LIGAR LANTERNA';

  botao.setAttribute('aria-pressed', String(ligada));
}

botao.addEventListener('click', async () => {
  if (ocupado) return;

  ocupado = true;
  botao.disabled = true;

  try {
    if (Capacitor.isNativePlatform()) {
      const disponibilidade = await Torch.isAvailable();

      if (!disponibilidade.available) {
        mensagem.textContent =
          'Este aparelho não disponibiliza a lanterna.';
        return;
      }

      if (ligada) {
        await Torch.disable();
        ligada = false;
      } else {
        await Torch.enable();
        ligada = true;
      }

      mensagem.textContent = ligada
        ? 'Flash físico ligado.'
        : 'Flash físico desligado.';
    } else {
      // No computador, testa somente a interface.
      ligada = !ligada;
      mensagem.textContent =
        'Demonstração visual: o flash físico não foi acionado.';
    }

    atualizarInterface();
  } catch (erro) {
    console.error('Erro ao controlar a lanterna:', erro);

    mensagem.textContent =
      'Não foi possível controlar a lanterna. Tente novamente.';
  } finally {
    ocupado = false;
    botao.disabled = false;
  }
});

atualizarInterface();
